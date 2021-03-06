package org.roug.osnine;

import static org.junit.Assert.assertEquals;
import org.junit.Before;
import org.junit.Test;

public class Framework {

    protected MC6809 myTestCPU;

    @Before
    public void setUp() {
        myTestCPU = new MC6809(0x2000);
    }

    void setA(int value) {
        myTestCPU.a.set(value);
    }

    void assertA(int exp) {
        assertEquals(exp, myTestCPU.a.intValue());
    }

    void setB(int value) {
        myTestCPU.b.set(value);
    }

    void assertB(int exp) {
        assertEquals(exp, myTestCPU.b.intValue());
    }

    void setCC(int value) {
        myTestCPU.cc.set(value);
    }

    void assertCC(int exp) {
        assertEquals(exp, myTestCPU.cc.intValue());
    }

    void setD(int value) {
        myTestCPU.d.set(value);
    }

    void assertD(int exp) {
        assertEquals(exp, myTestCPU.d.intValue());
    }

    void setPC(int value) {
        myTestCPU.pc.set(value);
    }

    void assertPC(int exp) {
        assertEquals(exp, myTestCPU.pc.intValue());
    }

    void setX(int value) {
        myTestCPU.x.set(value);
    }

    void assertX(int exp) {
        assertEquals(exp, myTestCPU.x.intValue());
    }

    void setY(int value) {
        myTestCPU.y.set(value);
    }

    void assertY(int exp) {
        assertEquals(exp, myTestCPU.y.intValue());
    }

    void writebyte(int loc, int value) {
        myTestCPU.write(loc, value);
    }

    void writeword(int loc, int value) {
        myTestCPU.write_word(loc, value);
    }
}
