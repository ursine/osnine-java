package org.roug.osnine.v6809;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Properties;
import org.roug.osnine.Acia6551Console;
import org.roug.osnine.HWClock;
import org.roug.osnine.IRQBeat;
import org.roug.osnine.Loader;
import org.roug.osnine.MC6809;
import org.roug.osnine.OptionParser;
import org.roug.osnine.VirtualDisk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class V6809 {

    private static final Logger LOGGER = LoggerFactory.getLogger(V6809.class);

    private static MC6809 cpu;

    private enum KnownVectors {
            reset(MC6809.RESET_ADDR),
            swi(MC6809.SWI_ADDR),
            swi1(MC6809.SWI_ADDR),
            irq(MC6809.IRQ_ADDR),
            firq(MC6809.FIRQ_ADDR),
            swi2(MC6809.SWI2_ADDR),
            swi3(MC6809.SWI3_ADDR);
            int value;
            KnownVectors(int value) { this.value = value; }
            KnownVectors() {}
    }

    private static int getIntProperty(Properties props, String key) throws Exception {
        return Integer.decode(props.getProperty(key)).intValue();
    }

    private static int getVectorAddress(String key) {
        for (KnownVectors v : KnownVectors.values()) {
            if (key.equalsIgnoreCase(v.toString())) {
                return v.value;
            }
        }
        return Integer.decode(key);
    }

    /**
     * Load modules.
     */
    private static int loadModules(String[] files) throws Exception {
        int loadAddress = 0;
        byte[] buf = new byte[0x10000];

        for (String fileToLoad : files) {
            if (fileToLoad.startsWith("@")) {
                loadAddress = getVectorAddress(fileToLoad.substring(1));
                continue;
            }
            if (fileToLoad.startsWith("$")) {
                loadAddress = loadHexString(loadAddress, fileToLoad.substring(1));
                continue;
            }
            LOGGER.debug("Loading {} at {}", fileToLoad, Integer.toHexString(loadAddress));

            InputStream moduleStream;
            if (fileToLoad.contains("/")) {
                moduleStream = new FileInputStream(fileToLoad);
            } else {
                moduleStream = V6809.class.getResourceAsStream("/" + fileToLoad);
            }
            int b = moduleStream.read();
            while (b != -1) {
                cpu.write(loadAddress, b);
                loadAddress++;
                b = moduleStream.read();
            }
            moduleStream.close();
        }
        LOGGER.debug("End address: {}", Integer.toHexString(loadAddress));
        return loadAddress;
    }

    public static void main(String[] args) throws Exception {
        OptionParser op = new OptionParser(args, "c:");

        String propertiesFile = op.getOptionArgument("c");
        if (propertiesFile == null) {
            propertiesFile = "v6809.properties";
        }

        Properties props = new Properties();

//      InputStream propertiesStream = V6809.class.getResourceAsStream("/v6809.properties");
        InputStream propertiesStream = new FileInputStream(propertiesFile);
        props.load(propertiesStream);
        propertiesStream.close();

        int memory = getIntProperty(props, "memory");
        cpu = new MC6809(memory);

        IRQBeat heartBeat = new IRQBeat(0xff00, cpu);
        cpu.insertMemorySegment(heartBeat);

        HWClock hwClock = new HWClock(0xff10, cpu);
        cpu.insertMemorySegment(hwClock);

        Acia6551Console console = new Acia6551Console(0xff04, cpu);
        cpu.insertMemorySegment(console);

        VirtualDisk disk = new VirtualDisk(0xff40, "OS9.dsk");
        cpu.insertMemorySegment(disk);

        disk = new VirtualDisk(0xff44, "DISK2.dsk");
        cpu.insertMemorySegment(disk);

        String segmentList = props.getProperty("load");
        String[] segments = segmentList.split("\\s+");
        int endAddress = loadModules(segments);

//      for (String segment : segments) {
//          String[] files = props.getProperty(segment).split("\\s+");
//          int endAddress = loadModules(files);
//      }

        int start = cpu.read_word(MC6809.RESET_ADDR);
        LOGGER.debug("Reset address: {}", cpu.read_word(MC6809.RESET_ADDR));
        cpu.reset();
        if (props.containsKey("start")) {
            start = getIntProperty(props, "start");
        }
        cpu.pc.set(start);
        LOGGER.debug("Starting at: {}", cpu.pc.intValue());
        cpu.run();
        System.out.flush();
    }


    /**
     * Load a hexstring into memory.
     *
     * @return the new location of the load pointer.
     */
    private static int loadHexString(int loadAddress, String input) {
	int len = input.length();

	if (len == 0) {
	    return loadAddress;
	}

	int startOffset;
	if (len % 2 != 0) {
            cpu.write(loadAddress, Character.digit(input.charAt(0), 16));
	    startOffset = 1;
	} else {
	    startOffset = 0;
	}

	for (int i = startOffset; i < len; i += 2) {
	    cpu.write(loadAddress + (i + 1) / 2,
                    (Character.digit(input.charAt(i), 16) << 4)
		    + Character.digit(input.charAt(i + 1), 16));
	}
        int loadSize = startOffset + (len / 2);
        LOGGER.debug("Loading {} bytes at {}", loadSize, Integer.toHexString(loadAddress));
        loadAddress += loadSize;
        return loadAddress;
    }
}
