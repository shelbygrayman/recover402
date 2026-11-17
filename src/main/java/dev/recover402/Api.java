package dev.recover402;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
/** Loopback-only local sidecar; never expose this unauthenticated endpoint publicly. */
public final class Api {
    private Api() {}
    public static void main(String[] args) throws Exception {
        if(args.length!=2)throw new IllegalArgumentException("Api JOURNAL PORT");
        Journal j=new Journal(Path.of(args[0]));
        HttpServer server=HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(),Integer.parseInt(args[1])),16);
        server.createContext("/attempts",exchange->{
            int status=200; Object output;
            try {
                if(!exchange.getRequestURI().getPath().equals("/attempts"))throw new IllegalArgumentException("unknown route");
                if(exchange.getRequestMethod().equals("GET")) output=j.all().stream().map(Main::view).toList();
                else if(exchange.getRequestMethod().equals("POST")) {
                    byte[] body=exchange.getRequestBody().readNBytes(16_385);
                    if(body.length>16_384)throw new IllegalArgumentException("request too large");
                    Map<?,?> m=Json.object(Json.parse(new String(body,StandardCharsets.UTF_8)));
                    String id=Json.string(m.get("id"));
                    output=switch(Json.string(m.get("action"))) {
                        case "claim"->Main.reservation(j.reserve(id,Json.string(m.get("network")),Json.string(m.get("asset")),Json.string(m.get("payer")),Json.string(m.get("payTo")),Json.string(m.get("amount")),Json.string(m.get("requestDigest"))));
                        case "bind"->Main.view(j.bind(id,Json.string(m.get("transaction"))));
                        case "delivered"->Main.view(j.delivery(id));
                        default->throw new IllegalArgumentException("unsupported action");
                    };
                } else { status=405; output=Map.of("error","method not allowed"); }
            }catch(Exception e){status=400;output=Map.of("error",e.getClass().getSimpleName());}
            byte[] response=Json.encode(output).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(status,response.length);
            try(var out=exchange.getResponseBody()){out.write(response);}
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        Runtime.getRuntime().addShutdownHook(new Thread(()->{server.stop(0);try{j.close();}catch(Exception ignored){}}));
        server.start();System.out.println("Listening on "+server.getAddress());
    }
}
