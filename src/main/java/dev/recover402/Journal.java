package dev.recover402;

import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.zip.CRC32;

/** Single-writer durable attempt journal. No signatures or wallet secrets are stored. */
public final class Journal implements AutoCloseable {
    public enum Settlement { UNRESOLVED, TRANSFER_FINALIZED, TRANSACTION_REVERTED }
    public enum Delivery { UNKNOWN, OBSERVED }
    public record Attempt(String id, String fingerprint, String network, String asset,
                          String payer, String payTo, String amount, String requestDigest,
                          String transaction, Settlement settlement, Delivery delivery) {}
    public record Reservation(Attempt attempt, boolean newlyCreated) {}
    public synchronized Reservation reserve(String id, String network, String asset, String payer, String payTo, String amount, String requestDigest) throws IOException {
        boolean created = !attempts.containsKey(id);
        return new Reservation(claim(id,network,asset,payer,payTo,amount,requestDigest),created);
    }
    private final FileChannel channel;
    private final FileLock lock;
    private final Map<String,Attempt> attempts = new LinkedHashMap<>();
    private final List<String> history = new ArrayList<>();
    private boolean poisoned;

    public Journal(Path path) throws IOException {
        // Parent must already exist; deploy on a local filesystem supporting force and locks.
        boolean fresh = !Files.exists(path);
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileLock acquired = null;
        try {
            acquired = channel.tryLock();
            if (acquired == null) throw new IOException("journal already locked");
            lock = acquired;
            if (fresh) {
                channel.force(true);
                try (var parent = FileChannel.open(path.toAbsolutePath().getParent(), StandardOpenOption.READ)) { parent.force(true); }
            }
            replay();
        } catch (Exception e) {
            if (acquired != null) acquired.release();
            channel.close();
            throw e;
        }
    }
    public static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    private static String address(String s) {
        if (!s.matches("0x[0-9a-fA-F]{40}")) throw new IllegalArgumentException("invalid EVM address");
        return s.toLowerCase(Locale.ROOT);
    }
    public synchronized Attempt claim(String id, String network, String asset, String payer, String payTo, String amount, String requestDigest) throws IOException {
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException("invalid attempt id");
        if (!network.matches("eip155:[1-9][0-9]{0,18}")) throw new IllegalArgumentException("invalid EVM network");
        asset = address(asset); payer = address(payer); payTo = address(payTo);
        if (!amount.matches("[1-9][0-9]{0,77}") || new java.math.BigInteger(amount).bitLength() > 256) throw new IllegalArgumentException("invalid atomic amount");
        if (!requestDigest.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("request SHA-256 required");
        String fp = digest(String.join("|", network, asset, payer, payTo, amount, requestDigest));
        Attempt old = attempts.get(id);
        if (old != null) {
            if (!old.fingerprint.equals(fp)) throw new IllegalArgumentException("attempt id reused with different terms");
            return old;
        }
        Attempt a = new Attempt(id, fp, network, asset, payer, payTo, amount, requestDigest, "", Settlement.UNRESOLVED, Delivery.UNKNOWN);
        append(a); return a;
    }
    public synchronized Attempt bind(String id, String tx) throws IOException {
        Attempt a = get(id);
        if (!tx.matches("0x[0-9a-fA-F]{64}")) throw new IllegalArgumentException("invalid transaction hash");
        tx = tx.toLowerCase(Locale.ROOT);
        if (!a.transaction.isEmpty() && !a.transaction.equals(tx)) throw new IllegalArgumentException("transaction cannot be replaced");
        if (a.transaction.equals(tx)) return a;
        for (Attempt other : attempts.values()) {
            if (!other.id.equals(id) && other.network.equals(a.network) && other.transaction.equals(tx))
                throw new IllegalArgumentException("transaction already bound to another attempt on this network");
        }
        return update(a, tx, a.settlement, a.delivery);
    }
    synchronized Attempt evidence(String id, Settlement state) throws IOException {
        Attempt a = get(id);
        if (a.settlement != Settlement.UNRESOLVED && a.settlement != state) throw new IllegalStateException("conflicting settlement evidence");
        if (a.settlement == state) return a;
        return update(a, a.transaction, state, a.delivery);
    }
    public synchronized Attempt delivery(String id) throws IOException {
        Attempt a = get(id);
        if (a.delivery == Delivery.OBSERVED) return a;
        return update(a, a.transaction, a.settlement, Delivery.OBSERVED);
    }
    private Attempt update(Attempt a, String tx, Settlement s, Delivery d) throws IOException {
        Attempt b = new Attempt(a.id, a.fingerprint, a.network, a.asset, a.payer, a.payTo, a.amount, a.requestDigest, tx, s, d);
        append(b); return b;
    }
    public synchronized Attempt get(String id) {
        Attempt a = attempts.get(id);
        if (a == null) throw new IllegalArgumentException("unknown attempt");
        return a;
    }
    public synchronized List<Attempt> all() { return List.copyOf(attempts.values()); }
    public synchronized List<String> timeline() { return List.copyOf(history); }
    private void append(Attempt a) throws IOException {
        if (poisoned) throw new IOException("journal write failed previously; reopen required");
        String payload = String.join("\t", a.id,a.fingerprint,a.network,a.asset,a.payer,a.payTo,a.amount,a.requestDigest,a.transaction,a.settlement.name(),a.delivery.name());
        byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
        CRC32 crc = new CRC32(); crc.update(bytes);
        String line = Base64.getEncoder().encodeToString(bytes) + ":" + Long.toHexString(crc.getValue()) + "\n";
        try {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(line);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true); // Do not expose success before the record is durable.
        } catch (IOException e) { poisoned = true; throw e; }
        attempts.put(a.id,a); history.add(payload);
    }
    private void replay() throws IOException {
        if (channel.size() > 256L * 1024 * 1024) throw new IOException("journal exceeds prototype 256 MiB limit");
        ByteBuffer b = ByteBuffer.allocate((int)channel.size());
        while (b.hasRemaining() && channel.read(b) >= 0) {}
        byte[] bytes = b.array(); int start = 0;
        for (int i=0;i<bytes.length;i++) if (bytes[i]=='\n') {
            try {
                String line = new String(bytes,start,i-start,StandardCharsets.UTF_8);
                int split = line.lastIndexOf(':');
                byte[] payload = Base64.getDecoder().decode(line.substring(0,split));
                CRC32 crc = new CRC32(); crc.update(payload);
                if (crc.getValue()!=Long.parseUnsignedLong(line.substring(split+1),16)) throw new IllegalArgumentException("checksum");
                String p = new String(payload,StandardCharsets.UTF_8); String[] f=p.split("\t",-1);
                if(f.length!=11) throw new IllegalArgumentException("record version");
                Attempt a=new Attempt(f[0],f[1],f[2],f[3],f[4],f[5],f[6],f[7],f[8],Settlement.valueOf(f[9]),Delivery.valueOf(f[10]));
                attempts.put(a.id,a); history.add(p);
            } catch(RuntimeException e) { throw new IOException("corrupt complete record at byte "+start,e); }
            start=i+1;
        }
        // An incomplete last record was never acknowledged. Keep prior durable state.
        if(start<bytes.length) { channel.truncate(start); channel.force(true); }
        channel.position(start);
    }
    @Override public synchronized void close() throws IOException { try { lock.release(); } finally { channel.close(); } }
}
