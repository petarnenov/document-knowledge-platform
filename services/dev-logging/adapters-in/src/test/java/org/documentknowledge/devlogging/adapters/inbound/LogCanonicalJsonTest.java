package org.documentknowledge.devlogging.adapters.inbound;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.documentknowledge.devlogging.domain.LogFailure;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class LogCanonicalJsonTest {
    String canonical(Object value){return new String(LogCanonicalJson.bytes(value),StandardCharsets.UTF_8);}
    @Test void canonicalizesUtf16KeyOrderAndSafePrimitiveSubsetWithoutUnicodeNormalization() {
        var row=new LinkedHashMap<String,Object>();row.put("\ue000",1);row.put("😀",true);row.put("a",List.of(0,9007199254740991L,false,"é","e\u0301"));
        assertEquals("{\"a\":[0,9007199254740991,false,\"é\",\"é\"],\"😀\":true,\"\ue000\":1}",canonical(row));
        assertEquals("\"\\\"\\\\\\b\\t\\n\\f\\r\\u0001\"",canonical("\"\\\b\t\n\f\r\u0001"));
    }
    @Test void refusesUnsupportedNumbersNullKeysAndBrokenUnicode() {
        for(Object invalid:new Object[]{null,1.0,-1,9007199254740992L,Map.of(1,"bad"),new Object(),"\ud800","\udc00","\ud800x"})
            assertThrows(LogFailure.class,()->LogCanonicalJson.bytes(invalid));
    }
}
