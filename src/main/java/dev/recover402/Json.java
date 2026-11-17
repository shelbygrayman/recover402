package dev.recover402;
import java.util.*;
/** Small strict JSON codec for the dependency-free RPC adapter. */
public final class Json {
    private Json() {}
    public static Object parse(String s) {
        Parser p=new Parser(s); Object v=p.value(0); p.ws();
        if(p.i!=s.length()) throw new IllegalArgumentException("trailing JSON"); return v;
    }
    public static String encode(Object v) {
        if(v==null) return "null";
        if(v instanceof String s) {
            StringBuilder b=new StringBuilder("\"");
            for(char c:s.toCharArray()) switch(c) {
                case '"' -> b.append("\\\""); case '\\' -> b.append("\\\\");
                default -> { if(c<32) b.append(String.format("\\u%04x",(int)c)); else b.append(c); }
            }
            return b.append('"').toString();
        }
        if(v instanceof Boolean || v instanceof Number) return v.toString();
        if(v instanceof Map<?,?> m) return "{"+String.join(",",m.entrySet().stream().map(e->encode(e.getKey().toString())+":"+encode(e.getValue())).toList())+"}";
        if(v instanceof Collection<?> c) return "["+String.join(",",c.stream().map(Json::encode).toList())+"]";
        throw new IllegalArgumentException("unsupported JSON value");
    }
    public static Map<?,?> object(Object v) { if(v instanceof Map<?,?> m) return m; throw new IllegalArgumentException("JSON object required"); }
    public static String string(Object v) { if(v instanceof String s) return s; throw new IllegalArgumentException("JSON string required"); }
    private static final class Parser {
        final String s; int i;
        Parser(String s) {this.s=s;}
        void ws() { while(i<s.length() && " \t\r\n".indexOf(s.charAt(i))>=0) i++; }
        char next() { if(i>=s.length()) throw new IllegalArgumentException("truncated JSON"); return s.charAt(i++); }
        Object value(int depth) {
            if(depth>64) throw new IllegalArgumentException("JSON nesting"); ws(); char c=next();
            if(c=='"') return str();
            if(c=='{') {
                Map<String,Object> m=new LinkedHashMap<>(); ws(); if(i<s.length()&&s.charAt(i)=='}'){i++;return m;}
                do { ws(); if(next()!='"') throw new IllegalArgumentException("object key"); String k=str(); ws(); if(next()!=':') throw new IllegalArgumentException("colon"); Object v=value(depth+1); if(m.containsKey(k)) throw new IllegalArgumentException("duplicate key"); m.put(k,v); ws(); c=next(); if(c=='}') return m; } while(c==',');
                throw new IllegalArgumentException("object delimiter");
            }
            if(c=='[') {
                List<Object> a=new ArrayList<>(); ws(); if(i<s.length()&&s.charAt(i)==']'){i++;return a;}
                do { a.add(value(depth+1)); ws(); c=next(); if(c==']')return a; }while(c==','); throw new IllegalArgumentException("array delimiter");
            }
            i--;
            for(String token:List.of("true","false","null")) if(s.startsWith(token,i)) {i+=token.length();return switch(token){case "true"->true;case "false"->false;default->null;};}
            int start=i; while(i<s.length()&&"0123456789eE+-.".indexOf(s.charAt(i))>=0)i++;
            String n=s.substring(start,i); if(!n.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IllegalArgumentException("JSON number"); return new java.math.BigDecimal(n);
        }
        String str() {
            StringBuilder b=new StringBuilder();
            while(true) {
                char c=next(); if(c=='"')return b.toString(); if(c<32)throw new IllegalArgumentException("JSON control character");
                if(c=='\\') { c=next(); switch(c) {case '"','\\','/'->b.append(c);case 'b'->b.append('\b');case 'f'->b.append('\f');case 'n'->b.append('\n');case 'r'->b.append('\r');case 't'->b.append('\t');case 'u'->{if(i+4>s.length())throw new IllegalArgumentException("unicode escape");b.append((char)Integer.parseInt(s.substring(i,i+4),16));i+=4;}default->throw new IllegalArgumentException("JSON escape");} }
                else b.append(c);
            }
        }
    }
}
