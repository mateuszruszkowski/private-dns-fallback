package io.github.privatednsfallback;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class DotProbeTest {
    private static byte[] reply() {
        return new byte[]{
            0x12,0x34,(byte)0x81,(byte)0x80,0,1,0,1,0,0,0,0,
            7,'e','x','a','m','p','l','e',3,'o','r','g',0,0,1,0,1,
            (byte)0xc0,0x0c,0,1,0,1,0,0,0,60,0,4,(byte)192,0,2,1
        };
    }

    @Test public void acceptsCompleteAnswerToOurQuestion() {
        assertTrue(DotProbe.validAnswer(reply(),0x1234));
    }

    @Test public void rejectsErrorsAndUnrelatedResponses() {
        assertFalse(DotProbe.validAnswer(reply(),17));
        byte[] msg = reply(); msg[3] |= 3;
        assertFalse(DotProbe.validAnswer(msg,0x1234));
        msg = reply(); msg[18] = 'z';
        assertFalse(DotProbe.validAnswer(msg,0x1234));
        msg = reply(); msg[2] |= 2;
        assertFalse(DotProbe.validAnswer(msg,0x1234));
    }

    @Test public void rejectsEveryTruncatedPrefix() {
        byte[] msg = reply();
        for (int length = 0; length < msg.length; length++) {
            assertFalse("prefix length " + length,DotProbe.validAnswer(Arrays.copyOf(msg,length),0x1234));
        }
    }
}
