package org.roug.osnine;

import java.io.IOException;
import java.lang.reflect.Constructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Rockwell 6551 Asynchronous Communications Interface Adapter (ACIA).
 * The Dragon 64 and Dragon Alpha have a hardware serial port driven
 * by a Rockwell 6551, mapped from $FF04-$FF07.
 */
public class Acia6551 extends MemorySegment implements Acia {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(Acia6551.class);

    /** Register selection flags. One per mapped address. */
    private static final int DATA_REG = 0;
    private static final int STAT_REG = 1;
    private static final int CMND_REG = 2;
    private static final int CTRL_REG = 3;

    /** Receiver Interrupt Request Disabled. */
    private static final int IRD = 2;
    /** Transmitter Interrupt Control. */
    private static final int TIC0 = 4;
    private static final int TIC1 = 8;

    /** Parity Error. */
    private static final int CR0 = 1;    // %00000001   PE
    /** Framing Error. */
    private static final int CR1 = 2;
    /** Overrun. */
    private static final int CR2 = 4;    // %00000100

    /** Receive Data Register Full. */
    private static final int RDRF = 8;    // %00001000

    /** Transmit Data Register Empty. */
    private static final int TDRE = 16;   // %00010000

    /** Data Carrier Detect. */
    private static final int DCD = 32;   // %00100000
    /** Data Set Ready. */
    private static final int CR6 = 64;   // %01000000
    /** Receive Interupt Enable. */
    private static final int CR7 = 128;  // %10000000

    /** Interupt Request. */
    private static final int IRQ = 128;

    private int transmitData;
    private int receiveData;
    private int controlRegister;
    private int commandRegister;

    boolean receiveIrqEnabled = false;
    boolean transmitIrqEnabled = false;

    private int statusRegister = TDRE;

    private String eolSequence = "\015";

    /** Reference to CPU for the purpose of sending IRQ. */
    private Bus8Motorola bus;

    /**
     * Constructor.
     *
     * @param start - First address location of the ACIA.
     * @param bus - The bus the ACIA is attached to.
     * @param args - additional arguments
     */
    public Acia6551(int start, Bus8Motorola bus, String... args) {
        super(start, start + 3);
        this.bus = bus;
        setDCD(false);  // There is no carrier
        loadUI(args[0]);
    }

    /**
     * Load the class that implements the user interface.
     *
     * @param guiClassStr the name of the Java class as a string.
     */
    private void loadUI(String guiClassStr) {
        LOGGER.debug("Loading class {}", guiClassStr);
        try {
            Class newClass = Class.forName(guiClassStr);
            Constructor<Runnable> constructor = newClass.getConstructor(Acia.class);
            Runnable threadInstance = constructor.newInstance(this);

            Thread reader = new Thread(threadInstance, "acia6551");
            reader.setDaemon(true);
            reader.start();
        } catch (Exception e) {
            LOGGER.error("Load failure", e);
            System.exit(1);
        }
    }

    /**
     * Set Data Carrier Detect. In telnet-based emulation this
     * is set to high when a client has connected to the socket.
     * When the client closes the connection, then the DCD goes to false.
     * In the 6551, the bit is inverted. I.e. bit 5 in the status register
     * is 1 when there is no carrier.
     *
     * @param detected - if there is a carrier
     */
    @Override
    public synchronized void setDCD(boolean detected) {
        int oldStatus = statusRegister;
        if (detected) {
            clearStatusBit(DCD);
        } else {
            setStatusBit(DCD);
        }
        if (oldStatus != statusRegister) {
            if (receiveIrqEnabled) {
                setStatusBit(IRQ);
                bus.signalIRQ(true);
            }
        }
    }

    /**
     * Set the End-of-line sequence. In OS-9 this is 0x0D.
     *
     * @param token - symbolic name for nl, crnl or cr.
     */
    public void setEol(String token) {
        if ("nl".equalsIgnoreCase(token)) {
            eolSequence = "\012";
        } else {
            if ("crnl".equalsIgnoreCase(token)) {
                eolSequence = "\015\012";
            } else {
                eolSequence = "\015";
            }
        }
    }

    /**
     * Is Receive register full?
     */
    private boolean isReceiveRegisterFull() {
        return (statusRegister & RDRF) == RDRF;
    }

    /**
     * Is Transmit register empty?
     */
    private boolean isTransmitRegisterEmpty() {
        return (statusRegister & TDRE) == TDRE;
    }

    @Override
    public void eolReceived() {
        for (int i = 0; i < eolSequence.length(); i++) {
            dataReceived(eolSequence.charAt(i));
        }
    }

    /**
     * Get interrupted by reader thread and get the byte.
     */
    public synchronized void dataReceived(int val) {
        // Wait until the CPU has taken the current byte
        while (isReceiveRegisterFull()) {
            try {
                wait();
            } catch (InterruptedException e) {
                LOGGER.info("InterruptedException", e);
            }
        }
        receiveData = val;
        setStatusBit(RDRF);   // We have set interrupt, Read register is full.
        if (receiveIrqEnabled) {
            raiseIRQ();
        }
        notifyAll();
    }

    /**
     * Let the LineWriter wait for the next character.
     */
    @Override
    public synchronized int waitForValueToTransmit() throws InterruptedException {
        while (isTransmitRegisterEmpty()) {
            wait();
        }
        int t = transmitData;
        setStatusBit(TDRE);     // Transmit register is empty now
        if (transmitIrqEnabled && isBitOff(statusRegister, DCD)) {
            raiseIRQ();
        }
        notifyAll();
        return t;
    }

    private void raiseIRQ() {
        if (isBitOff(statusRegister, IRQ)) {
            setStatusBit(IRQ);
            bus.signalIRQ(true);
        }
    }

    private void lowerIRQ() {
        if (isBitOn(statusRegister, IRQ)) {
            clearStatusBit(IRQ);
            bus.signalIRQ(false);
        }
    }

    @Override
    protected int load(int addr) {
        try {
            switch (addr - getStartAddress()) {
            case DATA_REG:
                return getReceivedValue();
            case STAT_REG:
                return getStatusReg();
            case CMND_REG:
                LOGGER.debug("Read command register");
                synchronized(this) {
                    return commandRegister;
                }
            case CTRL_REG:
                LOGGER.debug("Read control register");
                return controlRegister;
            }
        } catch (IOException e) {
            System.exit(1);
        }
        return 0;
    }

    @Override
    protected void store(int addr, int val) {
        switch (addr - getStartAddress()) {
        case DATA_REG:
            sendValue(val);
            break;
        case STAT_REG:
            reset();
            break;
        case CMND_REG:
            setCommandRegister(val);
            break;
        case CTRL_REG:
            setControlRegister(val);
            break;
        }
    }

    /**
     * Writing a byte to the status register resets the chip.
     */
    private synchronized void reset() {
        LOGGER.debug("Reset");
        statusRegister = TDRE;
        transmitData = 0;
        receiveData = 0;
        receiveIrqEnabled = false;
        transmitIrqEnabled = false;
        lowerIRQ();
    }

    /**
     * Get the status register. This must not wait.
     *
     * @return The contents of the status register.
     */
    private int getStatusReg() throws IOException {
        int stat = statusRegister;
        lowerIRQ();
        LOGGER.debug("StatusRegister: {}", stat);
        return stat & 0xFF;
    }

    /**
     * Transmit byte to the terminal. Producer.
     * Should only be called when the register is empty.
     *
     * @param value - Character to send.
     */
    private synchronized void sendValue(int val) {
        LOGGER.debug("Send value: {}", val);
        while (!isTransmitRegisterEmpty()) {
            try {
                wait();
            } catch (InterruptedException e) {
                LOGGER.info("InterruptedException in sendValue", e);
            }
        }
        transmitData = val;
        clearStatusBit(TDRE);    // Transmit register is not empty now
        notifyAll();
    }

    /**
     * Read the receiver data register.
     * RDRF goes to 0 when the processor reads the register.
     */
    private synchronized int getReceivedValue() throws IOException {
        LOGGER.debug("Received val: {}", receiveData);
        clearStatusBit(RDRF);    // Receive register is empty now
        if (transmitIrqEnabled && isTransmitRegisterEmpty()) {
            raiseIRQ();
        }
        notifyAll();
        return receiveData;
    }

    /**
     * Set control flags.
     * Value 5 = IRQ for receive on, IRQ for transmit on.
     */
    private synchronized void setCommandRegister(int data) {
        LOGGER.debug("Set command (Reg #{}): {}", CMND_REG, data);
        commandRegister = data;
        boolean activateIRQ = false;

        // Bit 1 controls receiver IRQ behavior
        receiveIrqEnabled = isBitOff(commandRegister, IRD);
        if (receiveIrqEnabled && isReceiveRegisterFull()) {
            activateIRQ = true;
        }
        // Bits 2 & 3 controls transmit IRQ behavior
        transmitIrqEnabled = (commandRegister & TIC1) == 0
                        && (commandRegister & TIC0) != 0;
        if (transmitIrqEnabled && isTransmitRegisterEmpty()) {
            activateIRQ = true;
        }

        if (activateIRQ) {
            raiseIRQ();
        } else {
            lowerIRQ();
        }

    }

    /**
     * Set the control register and associated state.
     *
     * @param data Data to write into the control register
     */
    private void setControlRegister(int data) {
        LOGGER.debug("Set control (Reg #{}): {}", CTRL_REG, data);
        controlRegister = data;
        int rate = 0;

        // If the value of the data is 0, this is a request to reset,
        // otherwise it's a control update.

        if (data == 0) {
            reset();
        } else {
            // Mask the lower three bits to get the baud rate.
            // Unused.
            int baudSelector = data & 0x0f;
        }
    }

    private static boolean isBitOn(int x, int n) {
        return (x & n) != 0;
    }

    private static boolean isBitOff(int x, int n) {
        return (x & n) == 0;
    }

    private void setStatusBit(int n) {
        statusRegister |= n;
    }

    private void clearStatusBit(int n) {
        statusRegister &= ~n;
    }

}
