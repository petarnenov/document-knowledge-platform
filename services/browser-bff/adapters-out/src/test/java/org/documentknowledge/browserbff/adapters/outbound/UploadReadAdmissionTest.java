package org.documentknowledge.browserbff.adapters.outbound;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UploadReadAdmissionTest {
    @Test void finiteSourcesRejectDuplicatesAndReuseOnlyTheirOwnLease() {
        var budget = new UploadReadAdmission();
        var source = new ByteArrayInputStream(new byte[0]);
        var first = budget.tryRetain(source);
        assertNotNull(first);
        assertNull(budget.tryRetain(source));
        var held = new ArrayList<UploadReadAdmission.Lease>();
        for (int i = 1; i < 64; i++) {
            var lease = budget.tryRetain(new ByteArrayInputStream(new byte[0]));
            assertNotNull(lease); held.add(lease);
        }
        assertEquals(64, budget.retained());
        assertNull(budget.tryRetain(new ByteArrayInputStream(new byte[0])));
        first.release();
        var replacement = budget.tryRetain(source);
        assertNotNull(replacement);
        first.release();
        assertEquals(64, budget.retained());
        replacement.release();
        held.forEach(UploadReadAdmission.Lease::release);
        assertEquals(0, budget.retained());
    }

    @Test void applicationsHaveIndependentIdentityBudgetsAndNullIsNotAnOwner() {
        var first = new UploadReadAdmission();
        var second = new UploadReadAdmission();
        InputStream source = new ByteArrayInputStream(new byte[0]);
        assertThrows(NullPointerException.class, () -> first.tryRetain(null));
        assertNotNull(first.tryRetain(source));
        assertEquals(0, second.retained());
        assertNotNull(second.tryRetain(source));
    }
}
