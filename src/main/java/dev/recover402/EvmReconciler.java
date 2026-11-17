package dev.recover402;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.math.BigInteger;
import java.util.*;

/** Read-only exact ERC-20 transfer matching. RPC operator is a trust boundary. */
public final class EvmReconciler {
    static final String TRANSFER="0xddf252ad1be2c89b69c2b068fc378daa952ba7f163c4a11628f55a4df523b3ef";
    private final URI rpc;
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public EvmReconciler(URI rpc) {
        if(!Set.of("http","https").contains(rpc.getScheme()) || rpc.getHost()==null || rpc.getUserInfo()!=null) throw new IllegalArgumentException("HTTP(S) RPC URL required");
        this.rpc=rpc;
    }
    Object call(String method,List<?> params) throws IOException,InterruptedException {
        String body=Json.encode(Map.of("jsonrpc","2.0","id",1,"method",method,"params",params));
        var request=HttpRequest.newBuilder(rpc).timeout(Duration.ofSeconds(5)).header("Content-Type","application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=client.send(request,HttpResponse.BodyHandlers.ofInputStream());
        byte[] data;
        try(var input=response.body()){data=input.readNBytes(1_048_577);}
        if(response.statusCode()!=200 || data.length>1_048_576) throw new IOException("RPC status or size limit");
        Map<?,?> result=Json.object(Json.parse(new String(data,java.nio.charset.StandardCharsets.UTF_8)));
        if(result.containsKey("error") || !result.containsKey("result") || !"2.0".equals(result.get("jsonrpc")) || !new java.math.BigDecimal(1).equals(result.get("id"))) throw new IOException("invalid RPC envelope");
        return result.get("result");
    }
    private static BigInteger hex(Object o) {
        String s=Json.string(o); if(!s.matches("0x[0-9a-fA-F]+"))throw new IllegalArgumentException("hex required"); return new BigInteger(s.substring(2),16);
    }
    private static String topic(String address) {return "0x"+"0".repeat(24)+address.substring(2);}
    public Journal.Attempt reconcile(Journal j,String id) throws IOException,InterruptedException {
        Journal.Attempt a=j.get(id);
        if(a.transaction().isEmpty() || a.settlement()!=Journal.Settlement.UNRESOLVED)return a;
        BigInteger chain=hex(call("eth_chainId",List.of()));
        if(!chain.toString().equals(a.network().substring(7))) throw new IOException("RPC network differs from attempt");
        Object raw=call("eth_getTransactionReceipt",List.of(a.transaction()));
        if(raw==null)return a;
        Map<?,?> r=Json.object(raw);
        if(!a.transaction().equalsIgnoreCase(Json.string(r.get("transactionHash"))))throw new IOException("receipt transaction mismatch");
        BigInteger height=hex(r.get("blockNumber"));
        Map<?,?> canonical=Json.object(call("eth_getBlockByNumber",List.of(r.get("blockNumber"),false)));
        Map<?,?> finalized=Json.object(call("eth_getBlockByNumber",List.of("finalized",false)));
        if(!Objects.equals(canonical.get("hash"),r.get("blockHash")) || !height.equals(hex(canonical.get("number"))) || hex(finalized.get("number")).compareTo(height)<0)return a;
        BigInteger status=hex(r.get("status"));
        if(status.equals(BigInteger.ZERO))return j.evidence(id,Journal.Settlement.TRANSACTION_REVERTED);
        if(!status.equals(BigInteger.ONE))throw new IOException("unexpected receipt status");
        if(!(r.get("logs") instanceof List<?> logs))throw new IOException("receipt logs required");
        int matches=0;
        for(Object entry:logs) {
            Map<?,?> log=Json.object(entry);
            if(!a.asset().equalsIgnoreCase(Json.string(log.get("address"))))continue;
            if(!(log.get("topics") instanceof List<?> t) || t.size()!=3 || !TRANSFER.equals(t.get(0)))continue;
            if(!topic(a.payer()).equalsIgnoreCase(Json.string(t.get(1))) || !topic(a.payTo()).equalsIgnoreCase(Json.string(t.get(2))))continue;
            if(!Json.string(log.get("data")).matches("0x[0-9a-fA-F]{64}"))continue;
            if(!a.transaction().equalsIgnoreCase(Json.string(log.get("transactionHash"))) || !Objects.equals(r.get("blockHash"),log.get("blockHash")) || !Boolean.FALSE.equals(log.get("removed")))continue;
            if(hex(log.get("data")).equals(new BigInteger(a.amount())))matches++;
        }
        // Multiple identical transfers are ambiguous; do not guess which one pays for the resource.
        return matches==1 ? j.evidence(id,Journal.Settlement.TRANSFER_FINALIZED) : a;
    }
}
