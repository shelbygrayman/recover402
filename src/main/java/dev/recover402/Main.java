package dev.recover402;
import java.nio.file.*;
import java.net.URI;
import java.util.*;
public final class Main {
    private Main() {}
    static Map<String,Object> view(Journal.Attempt a) {
        return Map.of("id",a.id(),"fingerprint",a.fingerprint(),"network",a.network(),"transaction",a.transaction(),"settlement",a.settlement().name(),"delivery",a.delivery().name(),"nextAction",a.settlement()==Journal.Settlement.UNRESOLVED?"RECONCILE_OR_REVIEW":"REVIEW_DELIVERY","automaticPaymentAllowed",false);
    }
    static Map<String,Object> reservation(Journal.Reservation r) {
        Map<String,Object> out=new LinkedHashMap<>(view(r.attempt()));
        out.put("newlyCreated",r.newlyCreated()); return out;
    }
    public static void main(String[] args) {
        try {
            if(args.length<2)throw new IllegalArgumentException("Usage: recover402 JOURNAL claim ID NETWORK ASSET PAYER PAYTO ATOMIC_AMOUNT REQUEST_SHA256 | bind ID TX | reconcile ID RPC_URL | delivered ID | inspect ID | list | timeline");
            try(Journal j=new Journal(Path.of(args[0]))) {
                Object result=switch(args[1]) {
                    case "claim"->{if(args.length!=9)throw new IllegalArgumentException("claim needs 7 arguments");yield reservation(j.reserve(args[2],args[3],args[4],args[5],args[6],args[7],args[8]));}
                    case "bind"->view(j.bind(args[2],args[3]));
                    case "reconcile"->view(new EvmReconciler(URI.create(args[3])).reconcile(j,args[2]));
                    case "delivered"->view(j.delivery(args[2]));
                    case "inspect"->view(j.get(args[2]));
                    case "list"->j.all().stream().map(Main::view).toList();
                    case "timeline"->j.timeline();
                    default->throw new IllegalArgumentException("unknown command");
                }; System.out.println(Json.encode(result));
            }
        }catch(Exception e){ System.err.println("recover402: "+e.getClass().getSimpleName()+": "+e.getMessage());System.exit(2); }
    }
}
