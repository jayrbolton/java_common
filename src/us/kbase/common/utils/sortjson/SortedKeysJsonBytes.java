package us.kbase.common.utils.sortjson;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Class sorts map keys in JSON data stored in either in File or in byte array. 
 * Result of sorting is written into external output stream without modification 
 * of original data source. Code is optimized in the way of using as less memory 
 * as possible. The only case of large memory requirement is map with large 
 * count of keys is present in data. In order to sort keys of some map we need 
 * to store all keys of this map in memory. For default settings keys are stored 
 * in memory as byte arrays. So if the data contains few millions of keys in the
 * same map we need to keep in memory all these key values bytes plus about 24
 * bytes per key for mapping key to place of key-value data in data source.
 * @author Roman Sutormin (rsutormin)
 */
public class SortedKeysJsonBytes {
	private byte[] data;
	private boolean skipKeyDuplication = false;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Defines byte array as data source
	 * @param byteSource byte array data source
	 * @throws IOException
	 */
	public SortedKeysJsonBytes(byte[] byteSource) throws IOException {
		data = byteSource;
	}

	/**
	 * @return true if key duplication is skipped (ignored). false is default value.
	 */
	public boolean isSkipKeyDuplication() {
		return skipKeyDuplication;
	}

	/**
	 * Defines if key duplication should be skipped (ignored) or not. false means
	 * error is generated in case of duplication (default).
	 * @param skipKeyDuplication value to set
	 * @return this object for chaining
	 */
	public SortedKeysJsonBytes setSkipKeyDuplication(boolean skipKeyDuplication) {
		this.skipKeyDuplication = skipKeyDuplication;
		return this;
	}

	/**
	 * Method saves sorted data into output stream. It doesn't close internal input stream.
	 * So please call close() after calling this method. 
	 * @param os output stream for saving sorted result
	 * @return this object for chaining
	 * @throws IOException in case of problems with i/o or with JSON parsing
	 * @throws KeyDuplicationException in case of duplicated keys are found in the same map
	 * @throws TooManyKeysException 
	 */
	public void writeIntoStream(OutputStream os) 
			throws IOException, KeyDuplicationException {
		int[] pos = {0};
		List<Object> path = new ArrayList<Object>();
		JsonElement root = searchForElement(pos, path);
		UnthreadedBufferedOutputStream ubos = new UnthreadedBufferedOutputStream(os, 100000);
		root.write(data, ubos);
		ubos.flush();
	}

	public byte[] getSorted() 
			throws IOException, KeyDuplicationException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();
		writeIntoStream(baos);
		baos.close();
		return baos.toByteArray();
	}
	
	private static String getPathText(List<Object> path) {
		if (path.size() == 0)
			return "/";
		StringBuilder sb = new StringBuilder();
		for (Object obj : path) {
			String item = "" + obj;
			item = item.replaceAll(Pattern.quote("/"), "\\\\/");
			sb.append("/").append(item);
		}
		return sb.toString();
	}

	private JsonElement searchForElement(int[] pos, List<Object> path) throws IOException {
		int b = -1;
		while (true) {
			if (pos[0] >= data.length)
				throw new IOException("Mapping close bracket wasn't found");
			b = data[pos[0]++] & 0xff;
			if (b != ' ' && b != '\r' && b != '\n' && b != '\t')
				break;
		}
		if (b == '{') {
			return searchForMapCloseBracket(pos, path);
		} else if (b == '[') {
			return searchForArrayCloseBracket(pos, path);
		} else {
			int start = pos[0] - 1;
			if (b == '\"') {
				searchForEndQuot(pos, false);
			} else {
				while (true) {
					if (pos[0] >= data.length)
						break;
					b = data[pos[0]++] & 0xff;
					if (b == '}' || b == ']' || b == ',') {
						pos[0]--;
						break;
					}
				}
			}
			int length = pos[0] - start;
			return new JsonPrimitiveElement(start, length);
		}
	}

	
	private JsonMapElement searchForMapCloseBracket(int[] pos, List<Object> path) throws IOException {
		List<KeyValueLocation> ret = new ArrayList<KeyValueLocation>();
		boolean isBeforeField = true;
		String currentKey = null;
		int currentKeyStart = -1;
		int currentKeyStop = -1;
		JsonElement currentValue = null;
		while (true) {
			if (pos[0] >= data.length)
				throw new IOException("Mapping close bracket wasn't found");
			int b = data[pos[0]++] & 0xff;
			if (b == '}') {
				if (currentKey != null) {
					if (currentKeyStart < 0 || currentKeyStop < 0)
						throw new IOException("Value without key in mapping");
					ret.add(new KeyValueLocation(currentKey,currentKeyStart, currentKeyStop, currentValue));
					currentKey = null;
					currentKeyStart = -1;
					currentKeyStop = -1;
					currentValue = null;
				}
				break;
			} else if (b == '"') {
				if (isBeforeField) {
					currentKeyStart = pos[0] - 1;
					currentKey = searchForEndQuot(pos, true);
					currentKeyStop = pos[0] - 1;
				} else {
					throw new IllegalStateException();
				}
			} else if (b == ':') {
				if (!isBeforeField)
					throw new IOException("Unexpected colon sign in the middle of value text");
				currentValue = searchForElement(pos, path);
				isBeforeField = false;
			} else if (b == ',') {
					if (currentKey == null)
						throw new IOException("Comma in mapping without key-value pair before");
					if (currentKeyStart < 0 || currentKeyStop < 0)
						throw new IOException("Value without key in mapping");
					ret.add(new KeyValueLocation(currentKey, currentKeyStart, currentKeyStop, currentValue));
					currentKey = null;
					currentKeyStart = -1;
					currentKeyStop = -1;
					currentValue = null;
				isBeforeField = true;
			} else  {
				if (b != ' ' && b != '\r' && b != '\n' && b != '\t')
					throw new IOException("Unexpected character: " + (char)b);
			}
		}
		Collections.sort(ret);
		// TODO: check key duplication
		return new JsonMapElement(ret);
	}

	private JsonArrayElement searchForArrayCloseBracket(int[] pos, List<Object> path) throws IOException {
		List<JsonElement> items = new ArrayList<JsonElement>();
		if (pos[0] >= data.length)
			throw new IOException("Array close bracket wasn't found");
		int b = data[pos[0]++] & 0xff;
		if (b != ']') {
			pos[0]--;
			while (true) {
				items.add(searchForElement(pos, path));
				if (pos[0] >= data.length)
					throw new IOException("Array close bracket wasn't found");
				while (true) {
					if (pos[0] >= data.length)
						throw new IOException("Array close bracket wasn't found");
					b = data[pos[0]++] & 0xff;
					if (b != ' ' && b != '\r' && b != '\n' && b != '\t')
						break;
				}
				if (b == ']') {
					break;
				} else if (b != ',') {
					throw new IOException("Unexpected character: " + (char)b);
				}
			}
		}
		return new JsonArrayElement(items);
	}

	private String searchForEndQuot(int[] pos, boolean createString) throws IOException {
		ByteArrayOutputStream ret = null;
		if (createString) {
			ret = new ByteArrayOutputStream();
			ret.write('"');
		}
		while (true) {
			if (pos[0] >= data.length)
				throw new IOException("String close quot wasn't found");
			int b = data[pos[0]++] & 0xff;
			if (createString)
				ret.write(b);
			if (b == '"')
				break;
			if (b == '\\') {
				if (pos[0] >= data.length)
					throw new IOException("String close quot wasn't found");
				b = data[pos[0]++] & 0xff;
				if (createString)
					ret.write(b);
			}
		}
		if (createString)
			return MAPPER.readValue(ret.toByteArray(), String.class);
		return null;
	}

	private static interface JsonElement {
		public void write(byte[] source, OutputStream os) throws IOException;
	}

	private static class JsonPrimitiveElement implements JsonElement {
		int start;
		int length;
		
		public JsonPrimitiveElement(int start, int length) {
			this.start = start;
			this.length = length;
		}
		
		@Override
		public void write(byte[] source, OutputStream os) throws IOException {
			os.write(source, start, length);
		}
	}
	
	private static class JsonArrayElement implements JsonElement {
		List<JsonElement> items;

		JsonArrayElement(List<JsonElement> items) {
			this.items = items;
		}

		@Override
		public void write(byte[] source, OutputStream os) throws IOException {
			os.write('[');
			boolean first = true;
			for (JsonElement item : items) {
				if (!first)
					os.write(',');
				item.write(source, os);
				first = false;
			}
			os.write(']');
		}
	}

	private static class JsonMapElement implements JsonElement {
		List<KeyValueLocation> items;
		
		JsonMapElement(List<KeyValueLocation> items) {
			this.items = items;
		}
		
		@Override
		public void write(byte[] source, OutputStream os) throws IOException {
			os.write('{');
			boolean first = true;
			for (KeyValueLocation entry : items) {
				if (!first)
					os.write(',');
				os.write(source, entry.keyStart, entry.keyStop + 1 - entry.keyStart);
				os.write(':');
				entry.value.write(source, os);
				first = false;
			}
			os.write('}');
		}
	}
	
	private static class KeyValueLocation implements Comparable<KeyValueLocation> {
		String key;
		int keyStart;
		int keyStop;
		JsonElement value;

		public KeyValueLocation(String key, int keyStart, int keyStop, JsonElement value) {
			this.key = key;
			this.keyStart = keyStart;
			this.keyStop = keyStop;
			this.value = value;
		}

		@Override
		public int compareTo(KeyValueLocation o) {
			return key.compareTo(o.key);
		}
	}

	//removes thread safety code; per Roman 5x faster without it
	private static class UnthreadedBufferedOutputStream extends OutputStream {
		OutputStream out;
		byte buffer[];
		int bufSize;

		public UnthreadedBufferedOutputStream(OutputStream out, int size) throws IOException {
			this.out = out;
			if (size <= 0) {
				throw new IOException("Buffer size should be a positive number");
			}
			buffer = new byte[size];
		}

		void flushBuffer() throws IOException {
			if (bufSize > 0) {
				out.write(buffer, 0, bufSize);
				bufSize = 0;
			}
		}

		public void write(int b) throws IOException {
			if (bufSize >= buffer.length) {
				flushBuffer();
			}
			buffer[bufSize++] = (byte)b;
		}

		public void write(byte b[]) throws IOException {
			write(b, 0, b.length);
		}

		public void write(byte b[], int off, int len) throws IOException {
			if (len >= buffer.length) {
				flushBuffer();
				out.write(b, off, len);
				return;
			}
			if (len > buffer.length - bufSize) {
				flushBuffer();
			}
			System.arraycopy(b, off, buffer, bufSize, len);
			bufSize += len;
		}

		public void flush() throws IOException {
			flushBuffer();
			out.flush();
		}

		public void close() throws IOException {
			flush();
		}
	}
}