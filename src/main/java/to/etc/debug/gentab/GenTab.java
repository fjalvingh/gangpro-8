package to.etc.debug.gentab;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.LineNumberReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * @author <a href="mailto:jal@etc.to">Frits Jalvingh</a>
 * Created on 07-11-21.
 */
public class GenTab {


	static public void main(String[] args) throws Exception {
		new GenTab().run();
	}

	private void run() throws Exception {
		File inf = new File("/home/jal/numbers.txt");

		RenderSet rs = loadChars(inf);

		//-- Create a truth table from: value, x, y
		System.out.println("wire out;");

		int valueBits = getBits(rs.getList().size());
		int xbits = getBits(rs.getWidth());
		int ybits = getBits(rs.getHeight());

		System.out.println("input wire[" + (xbits - 1) + ":0] xpos;");
		System.out.println("input wire[" + (ybits - 1) + ":0] ypos;");
		System.out.println("input wire[" + (valueBits - 1) + ":0] value;");

		System.out.println("case({value, ypos, xpos})");
		for(int valueIndex = 0; valueIndex < rs.getList().size(); valueIndex++) {
			Render render = rs.getList().get(valueIndex);

			for(int y = 0; y < rs.getHeight(); y++) {
				for(int x = 0; x < rs.getWidth(); x++) {
					String pattern =
						getBitsOf(valueIndex, valueBits)
							+ getBitsOf(y, ybits)
							+ getBitsOf(x, xbits);
					boolean on = render.isOn(x, y);

					System.out.println("  "
						+ (valueBits + xbits + ybits) + "'b"
						+ pattern
						+ ": out = " + (on ? '1' : '0')
						+ ";"
					);
				}
			}
		}
		System.out.println("default: out=0;");
		System.out.println("endcase");
	}

	private String getBitsOf(int value, int count) {
		StringBuilder sb = new StringBuilder();
		for(int i = count; --i >= 0; ) {
			if((value & (1 << i)) != 0) {
				sb.append('1');
			} else {
				sb.append('0');
			}
		}
		return sb.toString();
	}

	private int getBits(int count) {
		if(count <= 2)
			return 1;
		if(count <= 4)
			return 2;
		if(count <= 8)
			return 3;
		if(count <= 16)
			return 4;
		throw new IllegalStateException("Invalid depth");
	}

	private RenderSet loadChars(File inf) throws Exception {
		List<Render> res = new ArrayList<>();

		try(LineNumberReader lnr = new LineNumberReader(new InputStreamReader(new FileInputStream(inf), StandardCharsets.UTF_8))) {


			String line;
			int w = 0, h = 0;
			Render currentChar = null;
			int phase = 0;
			while(null != (line = lnr.readLine())) {
				line = line.stripTrailing();
				if(line.length() > 0) {
					if(line.contains(":") && phase >= 2)
						phase = 1;

					switch(phase){
						case 0:
							//-- Expecting size
							String[] parts = line.split("x");
							if(parts.length != 2)
								throw new IOException("Expecting width x height");
							w = Integer.parseInt(parts[0].trim());
							h = Integer.parseInt(parts[1].trim());
							if(w == 0 || h == 0)
								throw new IOException("Expecting width x height");
							phase = 1;
							break;

						case 1:
							//-- Expecting a character code
							if(line.length() != 2 || line.charAt(1) != ':')
								throw new IOException("Expecting something like '0:' to indicate a new character");
							currentChar = new Render(line.charAt(0), h);
							res.add(currentChar);
							phase = 2;
							break;

						default:
							int y = phase - 2;
							if(y >= h)
								throw new IOException("Too many lines for character " + currentChar);
							if(line.length() > w)
								throw new IOException("Too many pixels for character " + currentChar + " on line " + y);
							currentChar.getPattern()[y] = line;
							phase++;
							break;
					}
				}
			}
			return new RenderSet(res, w, h);
		}
	}

	private class RenderSet {
		private final List<Render> m_list;

		private final int m_width;

		private final int m_height;

		public RenderSet(List<Render> list, int width, int height) {
			m_list = list;
			m_width = width;
			m_height = height;
		}

		public List<Render> getList() {
			return m_list;
		}

		public int getWidth() {
			return m_width;
		}

		public int getHeight() {
			return m_height;
		}
	}

	private class Render {
		private final char m_character;

		private final String[] m_pattern;

		public Render(char character, int height) {
			m_character = character;
			m_pattern = new String[height];
		}

		public String[] getPattern() {
			return m_pattern;
		}

		public boolean isOn(int x, int y) {
			if(y >= m_pattern.length)
				return false;
			String s = m_pattern[y];
			if(s == null)
				return false;
			if(x >= s.length())
				return false;
			return !Character.isWhitespace(s.charAt(x));
		}

		@Override
		public String toString() {
			return "" + m_character;
		}
	}

}
