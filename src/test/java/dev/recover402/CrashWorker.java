package dev.recover402;
import java.nio.file.Path;
public final class CrashWorker {
    public static void main(String[] args)throws Exception {
        try(Journal j=new Journal(Path.of(args[0]))) {
            if(args[1].equals("write")) {
                Checks.claim(j,"attempt");j.bind("attempt",Checks.TX);
                System.out.println("READY");System.out.flush();Thread.sleep(60000);
            }else System.out.println(Json.encode(java.util.Map.of("recovered",j.get("attempt").transaction().equals(Checks.TX))));
        }
    }
}
