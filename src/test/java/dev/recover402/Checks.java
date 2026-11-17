package dev.recover402;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
public final class Checks {
    static final String ASSET="0x"+"11".repeat(20), PAYER="0x"+"22".repeat(20), PAYTO="0x"+"33".repeat(20), TX="0x"+"44".repeat(32), DIGEST=Journal.digest("GET /report");
    static Journal.Attempt claim(Journal j,String id)throws Exception{return j.claim(id,"eip155:8453",ASSET,PAYER,PAYTO,"1000",DIGEST);}
    static void check(boolean value,String why){if(!value)throw new AssertionError(why);}
    static void rejects(Throwing op)throws Exception{boolean rejected=false;try{op.run();}catch(IllegalArgumentException|IllegalStateException|java.io.IOException expected){rejected=true;}check(rejected,"expected rejection");}
    interface Throwing{void run()throws Exception;}
    public static void main(String[] args)throws Exception {
        Path dir=Files.createTempDirectory("recover402-check-"); Path path=dir.resolve("journal");
        try(Journal j=new Journal(path)) {
            claim(j,"one"); j.bind("one",TX);
            rejects(()->j.claim("one","eip155:8453",ASSET,PAYER,PAYTO,"1001",DIGEST));
            rejects(()->j.bind("one","0x"+"55".repeat(32)));
            rejects(()->{try(Journal ignored=new Journal(path)){check(ignored.all().isEmpty(),"lock unexpectedly acquired");}});
            try(var workers=Executors.newFixedThreadPool(16)) {
                java.util.concurrent.atomic.AtomicInteger winners=new java.util.concurrent.atomic.AtomicInteger();
                List<Future<?>> tasks=new ArrayList<>();
                for(int i=0;i<100;i++)tasks.add(workers.submit(()->{try{if(j.reserve("same","eip155:8453",ASSET,PAYER,PAYTO,"1000",DIGEST).newlyCreated())winners.incrementAndGet();}catch(Exception e){throw new RuntimeException(e);}}));
                for(Future<?> f:tasks)f.get();
                check(winners.get()==1,"reservation has multiple dispatch winners");
            }
            rejects(()->j.bind("same",TX));
            check(j.all().size()==2,"concurrent claims duplicated");
            check(j.timeline().size()==3,"duplicate journal writes");
            j.delivery("one"); check(j.get("one").settlement()==Journal.Settlement.UNRESOLVED,"delivery implies settlement");
            rejects(()->j.claim("bad","eip155:8453",ASSET,PAYER,PAYTO,"0",DIGEST));
            rejects(()->Json.parse("{\"a\":1,\"a\":2}")); rejects(()->Json.parse("01"));
        }
        try(Journal j=new Journal(dir.resolve("pre-bind"))){claim(j,"attempt");}
        try(Journal j=new Journal(dir.resolve("pre-bind"))){check(j.get("attempt").transaction().isEmpty(),"invented missing transaction");check(j.get("attempt").settlement()==Journal.Settlement.UNRESOLVED,"missing hash resolved");}
        Files.writeString(path,"incomplete",StandardOpenOption.APPEND);
        try(Journal j=new Journal(path)){check(j.get("one").transaction().equals(TX),"restart lost tx");check(j.get("one").delivery()==Journal.Delivery.OBSERVED,"restart lost delivery");}
        byte[] bytes=Files.readAllBytes(path);bytes[10]^=1;Files.write(path,bytes);
        rejects(()->{try(Journal ignored=new Journal(path)){check(ignored.all().isEmpty(),"corruption accepted");}});
        Path perf=dir.resolve("perf");long[] times=new long[1000];
        try(Journal j=new Journal(perf)){for(int i=0;i<100;i++)claim(j,"warm"+i);for(int i=0;i<times.length;i++){long start=System.nanoTime();claim(j,"run"+i);times[i]=System.nanoTime()-start;}}
        Arrays.sort(times);
        System.out.println(Json.encode(Map.of("checks","passed","timed_claims",times.length,"claim_p50_us",times[499]/1000.0,"claim_p95_us",times[949]/1000.0,"claim_p99_us",times[989]/1000.0,"note","force(true) acknowledged writes; local filesystem only; no throughput or network claim")));
    }
}
