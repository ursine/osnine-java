package org.roug.osnine;

import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

class Acceptor implements Runnable {

    private static final Logger LOGGER = 
            LoggerFactory.getLogger(Acceptor.class);

    private Acia6551Telnet acia;

    private OutputStream clientOut;

    private InputStream clientIn;

    private boolean[] modes = {
        false,   // Transmit binary
        false,   // Echo
        false,   // Reconnection
        false,   // Suppress GA
        false,   // Approx message size
        false,   // Status
        false,   // Timing mark
    };

    /**
     * Constructor.
     */
    Acceptor(Acia6551Telnet acia) {
        this.acia = acia;
    }

    public void run() {
        final int portNumber = 2323;
        ServerSocket serverSocket;

        LOGGER.debug("Acceptor thread started");
        try {
            serverSocket = new ServerSocket(portNumber);
        } catch (Exception e) {
            LOGGER.error("Unable to create listening socket");
            return;
        }

        try {
            while (true) {
                Socket socket = serverSocket.accept();
                clientOut = socket.getOutputStream();
                clientIn = socket.getInputStream();
                acia.setDCD(true);

                Thread reader = new Thread(new LineReader(this), "acia-in");
                reader.start();

                Thread writer = new Thread(new LineWriter(this), "acia-out");
                writer.start();

                writer.join();
                socket.close();
                clientIn.close();
                clientOut.close();
                acia.setDCD(false);
            }
        } catch (InterruptedException e) {
            LOGGER.error("InterruptException", e);
        } catch (IOException e) {
            LOGGER.error("IOException", e);
            return;
        }
    }

    void dataReceived(int val) {
        acia.dataReceived(val);
    }

    int read() throws IOException {
        return clientIn.read();
    }

    void sendTelnetClient(int val) throws IOException {
        clientOut.write(val);
    }

    /**
     * Send from ACIA out.
     */
    int valueToTransmit() {
        return acia.valueToTransmit();
    }

    void flush() throws IOException {
        clientOut.flush();
    }

    /**
     * Tell telnet client I won't do this option.
     */
    void pleaseDo(int val) throws IOException {
       byte[] sequence = { (byte)TelnetState.IAC_CHAR,
               (byte) TelnetState.DO_CHAR, (byte) 0 };

       sequence[2] = (byte)val;
       clientOut.write(sequence, 0, 3);
    }

    /**
     * Tell telnet client I won't do this option.
     */
    void pleaseDont(int val) throws IOException {
       byte[] sequence = { (byte)TelnetState.IAC_CHAR,
               (byte) TelnetState.DONT_CHAR, (byte) 0 };

       sequence[2] = (byte)val;
       clientOut.write(sequence, 0, 3);
    }

    /**
     * Tell telnet client I won't do this option.
     */
    void wontDo(int val) throws IOException {
       byte[] sequence = { (byte)TelnetState.IAC_CHAR,
               (byte) TelnetState.WONT_CHAR, (byte) 0 };

        if (!modeIsSet(val)) {
            sequence[2] = (byte)val;
            clientOut.write(sequence, 0, 3);
            setMode(val, true);
        }
    }

    /**
     * Tell telnet client I will do this option.
     */
    void willDo(int val) throws IOException {
       byte[] sequence = { (byte)TelnetState.IAC_CHAR,
               (byte) TelnetState.WILL_CHAR, (byte) 0 };

        if (!modeIsSet(val)) {
            sequence[2] = (byte)val;
            clientOut.write(sequence, 0, 3);
            setMode(val, true);
        }
    }

    void setMode(int option, boolean value) {
        if (option < modes.length) {
            modes[option] = value;
        }
    }

    boolean modeIsSet(int option) {
        if (option < modes.length) {
            return modes[option];
        } else {
            return false;
        }
    }
}

/**
 * State engine to handle telnet protocol.
 */
enum TelnetState {

    NORMAL {
        @Override
        TelnetState handleCharacter(int receiveData, Acceptor handler)
                throws IOException {
            switch (receiveData) {
                case NULL_CHAR:
                case NEWLINE_CHAR:
                    return NORMAL; // Swallow newlines
                case IAC_CHAR:
                    return IAC;
                case DEL_CHAR:
                    handler.dataReceived(8);
                    return NORMAL;
                default:
                    handler.dataReceived(receiveData);
                    return NORMAL;
            }
        }
    },

    IAC {
        @Override
        TelnetState handleCharacter(int receiveData, Acceptor handler)
                throws IOException {
            switch (receiveData) {
                case IP_CHAR:
                    handler.dataReceived(INTR_CHAR);
                    return NORMAL;
                case DO_CHAR:
                    return DO;
                case DONT_CHAR:
                    return DONT;
                default:
                    return NORMAL;
            }
        }
    },

    /**
     * Client sends will.
     */
    WILL {
        @Override
        TelnetState handleCharacter(int receiveData, Acceptor handler)
                throws IOException {
            switch (receiveData) {
                case ECHO:
                    handler.pleaseDo(receiveData);
                    return NORMAL;
                default:        // Must respond to unsupported option
                    handler.pleaseDont(receiveData);
                    return NORMAL;
            }
        }
    },

    WONT {
        @Override
        TelnetState handleCharacter(int receiveData, Acceptor handler)
                throws IOException {
            switch (receiveData) {
                default:        // Must respond to unsupported option
                    return NORMAL;
            }
        }
    },

    DO {
        @Override
        TelnetState handleCharacter(int receiveData, Acceptor handler)
                throws IOException {
            switch (receiveData) {
                case SUPPRESS_GA:
                case ECHO:
                    handler.willDo(receiveData);
                    return NORMAL;
                default:        // Must respond to unsupported option
                    handler.wontDo(receiveData);
                    return NORMAL;
            }
        }
    },

    DONT {
        @Override
        TelnetState handleCharacter(int receiveData, Acceptor handler)
                throws IOException {
            switch (receiveData) {
                default:
                    return NORMAL;
            }
        }
    };

    private static final int NULL_CHAR = 0;
    private static final int INTR_CHAR = 3;
    private static final int QUIT_CHAR = 5;
    private static final int NEWLINE_CHAR = 10;
    private static final int DEL_CHAR = 127;   // VT100 send DEL and not BS

    static final int TRANSMIT_BINARY = 0;
    static final int ECHO = 1;
    static final int SUPPRESS_GA = 3;  // Suppress Go ahead
    static final int TIMING = 6;  // Telnet option Timing mark

    static final int NOP_CHAR = 241;  // No operation
    static final int IP_CHAR = 244;   // Interrupt Process
    static final int WILL_CHAR = 251;
    static final int WONT_CHAR = 252;
    static final int DO_CHAR = 253;
    static final int DONT_CHAR = 254;
    static final int IAC_CHAR = 255;  // Interpret as Command

    abstract TelnetState handleCharacter(int receiveData, Acceptor handler)
            throws IOException;
}

/**
 * Thread to listen to incoming data.
 */
class LineReader implements Runnable {

    private static final Logger LOGGER = 
            LoggerFactory.getLogger(LineReader.class);

    private TelnetState state = TelnetState.NORMAL;

    private Acceptor handler;

    /**
     * Constructor.
     */
    LineReader(Acceptor handler) throws IOException {
        this.handler = handler;
    }

    /**
     * Since this waits in read() we can busy loop on the rest.
     */
    public void run() {
        LOGGER.debug("Reader thread started");
        while (true) {
            try {
                int receiveData = handler.read();
                LOGGER.debug("Received {}", receiveData);
                state = state.handleCharacter(receiveData, handler);
            } catch (Exception e) {
                LOGGER.error("Exception", e);
                return;
            }
        }
    }
}

/**
 * Thread to write to socket.
 */
class LineWriter implements Runnable {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(LineWriter.class);

    private Acceptor handler;

    /**
     * Constructor.
     */
    LineWriter(Acceptor handler) throws IOException {
        this.handler = handler;
    }

    /**
     * Wait for a value from the Acia to transmit to the client terminal.
     * Send it, if exception, assume the connection is broken and exit.
     */
    public void run() {
        LOGGER.debug("Writer thread started");
        try {
            handler.willDo(TelnetState.SUPPRESS_GA);
            handler.willDo(TelnetState.ECHO);
            while (true) {
                int val = handler.valueToTransmit();
                handler.sendTelnetClient(val);
                handler.flush();
            }
        } catch (Exception e) {
            LOGGER.error("Broken connection", e);
            return;
        }
    }
}
