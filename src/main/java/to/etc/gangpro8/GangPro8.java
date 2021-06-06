package to.etc.gangpro8;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortInvalidPortException;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * @author <a href="mailto:jal@etc.to">Frits Jalvingh</a>
 * Created on 06-06-21.
 */
public class GangPro8 {
	@Option(name = "-p", aliases = {"--serial"}, usage = "The serial port to use, use something like /dev/ttyUSB0 on Linux", required = true)
	private String m_serialPort;

	@Option(name = "-d", aliases = {"--download"}, usage = "Download the content of the MASTER eprom socket to a file")
	private String m_downloadFile;

	@Option(name = "-f", aliases = {"--format"}, usage = "Format for the file: either binary or intel")
	private Format m_format = Format.binary;

	@Option(name = "-b", aliases = {"--bps", "--baud"}, usage = "The bit rate for the port, defaulting to 1200 (which is the device's default)\nValid are: 1200, 2400, 4800")
	private int m_bitRate = 1200;

	private SerialPort m_port;

	static public void main(String[] args) {
		try {
			new GangPro8().run(args);
		} catch(Exception x) {
			System.err.println("Fatal error: " + x);
			x.printStackTrace();
		}
	}

	private void run(String[] args) throws Exception {
		CmdLineParser p = new CmdLineParser(this);
		try {
			//-- Decode the tasks's arguments
			p.parseArgument(args);
		} catch(CmdLineException x) {
			System.err.println("Invalid arguments: " + x.getMessage());
			p.printUsage(System.err);
			System.exit(10);
		}

		try {
			if(m_downloadFile != null) {
				runDownload();
			} else {
				System.err.println("No action specified: expecting --download or --upload");
				System.exit(10);
			}
		} catch(MessageException mx) {
			System.err.println(mx.getMessage());
			System.exit(10);
		}
	}

	enum HexState {
		Colon,
		ByteCount,
		Address,
		RecordType,
		Data,
		Checksum,
		Eoln
	}

	private int m_gibberish;

	private int m_totalReceived;

	private HexState m_state = HexState.Colon;

	/** For states receiving hex data: the #of character (still) to read */
	private int m_hexToDo;

	/** For states receiving a value, the hex value collected (so far) */
	private int m_hexValue;

	/** The #of (remaining) bytes in the DATA record. */
	private int m_recordByteCount;

	/** The ADDRESS field for the data */
	private int m_address;

	private int m_expectedAddress;

	/** The sum of all hex bytes received so far. */
	private int m_lineSum;

	private int m_lineNumber;

	private ByteArrayOutputStream m_baos;

	private void runDownload() throws Exception {
		SerialPort port = m_port = open();
		try {
			explain("dlexpl");
			System.out.println("\nWaiting for data from the Gangpro-8");
			m_totalReceived = 0;
			m_state = HexState.Colon;
			m_hexValue = 0;
			m_baos = new ByteArrayOutputStream(65536);
			m_gibberish = 0;
			m_expectedAddress = 0;
			m_lineNumber = 1;

			byte[] cmd = "u\r\n".getBytes(StandardCharsets.UTF_8);
			port.writeBytes(cmd, cmd.length);

			while(runStates()) {
				//--
			}

			switch(m_format){
				default:
					throw new IllegalStateException("Unknown format: " + m_format);

				case binary:
					writeBinaryOutput();
					break;

				case intel:
					writeIntelOutput();
					break;
			}

		} finally {
			try {
				port.closePort();
			} catch(Exception x) {
				System.err.println("Exception closing the serial port: " + x);
			}
		}
	}

	private void writeBinaryOutput() {
		try(FileOutputStream fos = new FileOutputStream(new File(m_downloadFile))) {
			m_baos.close();
			byte[] bytes = m_baos.toByteArray();
			fos.write(bytes);
			System.out.println("Written 0x" + Integer.toHexString(bytes.length) + " (" + bytes.length + ") bytes to " + m_downloadFile);
		} catch(Exception x) {
			throw new MessageException("Failed to write file: " + x);
		}
	}

	private void writeIntelOutput() {
		try(OutputStreamWriter osw = new OutputStreamWriter(new FileOutputStream(new File(m_downloadFile)), StandardCharsets.UTF_8)) {
			m_baos.close();
			byte[] bytes = m_baos.toByteArray();
			int off = 0;
			while(off < bytes.length) {
				String ba = toIntelHex(bytes, off, 0x20, off);
				osw.write(ba);
				osw.write(System.lineSeparator());
				off += 0x20;
			}
			//-- And the last one
			osw.write(":00000001ff");
			osw.write(System.lineSeparator());
			System.out.println("Written 0x" + Integer.toHexString(bytes.length) + " (" + bytes.length + ") bytes to " + m_downloadFile + " in Intel Hex format");
		} catch(Exception x) {
			throw new MessageException("Failed to write file: " + x);
		}
	}


	private boolean runStates() {
		//-- Do we need hex chars?
		if(m_hexToDo > 0) {
			readHexValue();
			return true;
		}

		switch(m_state){
			default:
				throw new IllegalStateException("Unexpected state: " + m_state);

			case Colon:
				return waitColon();

			case ByteCount:
				//-- Once the byte count has been read just save it, then go to address
				if(m_hexValue > 255)
					fail("Byte count value " + m_hexValue + " in record invalid, it must be 0 <= count <= 255");
				m_recordByteCount = m_hexValue;
				return enter(HexState.Address, 4);

			case Address:
				m_address = m_hexValue;
				return enter(HexState.RecordType, 2);

			case RecordType:
				//-- Check the record type
				if(m_hexValue > 0x05) {
					fail("Invalid record type 0x" + Integer.toHexString(m_hexValue));
				}
				if(m_hexValue == 0x01) {
					//-- EOF -> we're done
					System.out.println("All data received");
					return false;
				}
				if(m_hexValue == 0) {
					if(m_recordByteCount == 0)
						fail("Byte count value " + m_hexValue + " in record invalid, it must be 0 <= count <= 255");

					if(m_address != m_expectedAddress) {
						fail("Incorrect address: expecting 0x" + Integer.toHexString(m_expectedAddress) + " but got 0x"  + Integer.toHexString(m_address));
					}

					return enter(HexState.Data, 2);
				}
				fail("The record type " + Integer.toHexString(m_hexValue) + " is not implemented");
				return false;

			case Data:
				//-- Got a byte of data.
				if(m_hexValue > 255) {
					fail("Unexpected data value 0x" + Integer.toHexString(m_hexValue));
				}
				m_baos.write(m_hexValue);
				m_hexValue = 0;

				if(m_expectedAddress == 0) {
					System.out.println("** receiving data **");
				}
				if((m_expectedAddress & 0xff) == 0) {
					System.out.println("... at address 0x" + Integer.toHexString(m_expectedAddress));
				}

				m_expectedAddress++;
				m_recordByteCount--;
				if(m_recordByteCount > 0) {
					return enter(HexState.Data, 2);
				}

				//-- All DATA gotten
				return enter(HexState.Checksum, 2);

			case Checksum:
				//-- check sum
				if((m_lineSum & 0xff) != 0) {
					fail("checksum error at line " + m_lineNumber + ", sum=0x" + Integer.toHexString(m_lineSum & 0xff));
				}
				m_lineSum = 0;
				return enter(HexState.Eoln, 0);

			case Eoln:
				return waitEoln();

		}
	}

	private boolean waitEoln() {
		for(;;) {
			int c = readChar();
			if(c == '\r' || c == '\n') {
				m_lineNumber++;
				return enter(HexState.Colon, 0);
			}
		}
	}

	private void fail(String s) {
		throw new MessageException(s);
	}

	private boolean waitColon() {
		int c = readChar();
		if(c == ':') {
			return enter(HexState.ByteCount, 2);
		}
		if(c == '\r' || c == '\n') {				// Left from previous line
			return true;
		}
		m_gibberish++;
		if(m_gibberish == 1) {
			System.out.println("Receiving something but it does not start with ':'; skipping spurious data");
		}
		return true;
	}

	private boolean enter(HexState state, int count) {
		m_state = state;
		m_hexToDo = count;
		m_hexValue = 0;
		return true;
	}

	private void readHexValue() {
		if(m_hexToDo <= 0)
			throw new IllegalStateException("Should have hex data to read");
		int c = readChar();
		c = Character.toUpperCase(c);
		if(c >= '0' && c <= '9') {
			m_hexValue = (m_hexValue << 4) | (c - '0');
		} else if(c >= 'A' && c <= 'F') {
			m_hexValue = (m_hexValue << 4) | (c - 'A' + 10);
		} else {
			throw new MessageException("Invalid character 0x" + Integer.toHexString(c) + " found while expecting a hex digit (" + m_state + ")");
		}
		m_hexToDo--;
		if((m_hexToDo & 0x01) == 0) {					// Every even #of chars add the just received byte to the line sum.
			m_lineSum += (m_hexValue & 0xff);
		}
	}

	private byte[] m_buffer = new byte[16];

	private int m_readIndex;

	private int m_readLength;

	private int readChar() {
		if(m_readIndex >= m_readIndex) {
			m_readLength = m_port.readBytes(m_buffer, m_buffer.length);
			m_readIndex = 0;
		}
		if(m_readLength <= 0) {
			throw new IllegalStateException("EOF on port: " + m_readLength);
		}
		int val = m_buffer[m_readIndex++] & 0xff;
		//System.out.println(".... " + m_state + " got 0x" + Integer.toHexString(val) + " '" + (char)val + "'");
		m_totalReceived++;
		return val;
	}


	/*----------------------------------------------------------------------*/
	/*	CODING:	Support code.												*/
	/*----------------------------------------------------------------------*/

	private int m_writeSum;

	private String toIntelHex(byte[] buf, int off, int len, int outputAddress) {
		if(len == 0 || len > 255)
			throw new IllegalStateException("Bad length");
		m_writeSum = 0;
		StringBuilder sb = new StringBuilder();
		sb.append(':');
		appendByte(sb, len);                        // byte count
		appendByte(sb, outputAddress >> 16);    // address
		appendByte(sb, outputAddress);
		appendByte(sb, 0);                    // Record type
		int end = off + len;
		if(end > buf.length)
			end = buf.length - off;
		if(end <= 0)
			throw new IllegalStateException("Invalid buffer size/length");
		while(off < end) {
			appendByte(sb, buf[off++]);                // data bytes
		}
		int sum = -m_writeSum;
		appendByte(sb, sum);                        // 2-complement of sum
		return sb.toString();
	}

	private void appendByte(StringBuilder sb, int value) {
		appendNibble(sb, value >> 4);
		appendNibble(sb, value);
		m_writeSum += (value & 0xff);
	}

	private void appendNibble(StringBuilder sb, int value) {
		sb.append(Character.forDigit(value & 0xff, 16));
	}

	/**
	 * Open serial port for use.
	 */
	private SerialPort open() throws Exception {
		try {
			SerialPort port = SerialPort.getCommPort(m_serialPort);
			port.setBaudRate(m_bitRate);
			port.setNumStopBits(2);
			port.setNumDataBits(8);
			port.setParity(SerialPort.NO_PARITY);
			//port.setFlowControl(SerialPort.FLOW_CONTROL_CTS_ENABLED | SerialPort.FLOW_CONTROL_RTS_ENABLED);
			port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);
			//port.setComPortTimeouts(SerialPort.TIMEOUT_READ_BLOCKING, 1000, 1000);
			port.openPort();
			return port;
		} catch(SerialPortInvalidPortException spx) {
			String ports = Arrays.stream(SerialPort.getCommPorts())
				.map(a -> "- " + a.getSystemPortName() + " " +  a.getDescriptivePortName() + "\n")
				.collect(Collectors.joining());

			throw new MessageException(spx.getMessage() + "\nAvailable ports are: " + ports);
		}
	}

	private void explain(String what) {
		try(InputStream is = getClass().getResourceAsStream("/" + what + ".txt")) {
			if(is == null) {
				throw new IllegalStateException("Missing resource: " + what);
			}
			try(LineNumberReader isr = new LineNumberReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
				String line;
				while(null != (line = isr.readLine())) {
					System.out.println(line);
				}
			}
		} catch(RuntimeException x) {
			throw x;
		} catch(Exception x) {
			throw new RuntimeException(x.getMessage(), x);
		}
	}
}
