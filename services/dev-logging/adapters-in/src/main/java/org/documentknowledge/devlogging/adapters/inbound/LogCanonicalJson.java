package org.documentknowledge.devlogging.adapters.inbound;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.documentknowledge.devlogging.domain.LogFailure;

/** RFC8785 subset for the closed diagnostic contract: strings, booleans and exact safe integers. */
final class LogCanonicalJson {
    static final long MAX_INTEGER=9007199254740991L;
    static byte[] bytes(Object value) {
        var output=new StringBuilder();append(output,value);return output.toString().getBytes(StandardCharsets.UTF_8);
    }
    private static void append(StringBuilder output,Object value) {
        if(value instanceof Map<?,?> map) {
            var keys=new ArrayList<String>();
            for(var key:map.keySet()) { if(!(key instanceof String)) throw invalid();keys.add((String)key); }
            Collections.sort(keys);output.append('{');boolean first=true;
            for(var key:keys) {
                if(!first)output.append(',');first=false;quote(output,key);output.append(':');append(output,map.get(key));
            }
            output.append('}');
        } else if(value instanceof List<?> list) {
            output.append('[');boolean first=true;
            for(var item:list) { if(!first)output.append(',');first=false;append(output,item); }output.append(']');
        } else if(value instanceof String text) quote(output,text);
        else if(value instanceof Boolean)output.append(value);
        else if(value instanceof Integer || value instanceof Long) {
            long number=((Number)value).longValue();if(number<0 || number>MAX_INTEGER)throw invalid();output.append(number);
        } else throw invalid();
    }
    private static void quote(StringBuilder output,String value) {
        output.append('"');
        for(int i=0;i<value.length();i++) {
            char ch=value.charAt(i);
            if(Character.isSurrogate(ch)) {
                if(!Character.isHighSurrogate(ch) || i+1>=value.length() || !Character.isLowSurrogate(value.charAt(i+1)))throw invalid();
                output.append(ch).append(value.charAt(++i));continue;
            }
            switch(ch) {
                case '"' -> output.append("\\\""); case '\\' -> output.append("\\\\");
                case '\b' -> output.append("\\b");case '\t' -> output.append("\\t");case '\n' -> output.append("\\n");
                case '\f' -> output.append("\\f");case '\r' -> output.append("\\r");
                default -> { if(ch<0x20)output.append(String.format(Locale.ROOT,"\\u%04x",(int)ch));else output.append(ch); }
            }
        }
        output.append('"');
    }
    private static LogFailure invalid(){return new LogFailure(LogFailure.Kind.INVALID);}
}
