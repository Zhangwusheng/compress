package com.ning.compress.lzf.util;

import java.io.*;

import com.ning.compress.lzf.*;

/**
 * Helper class that allows use of LZF compression even if a library requires
 * use of {@link FileInputStream}.
 *<p>
 * Note that use of this class is not recommended unless you absolutely must
 * use a {@link FileInputStream} instance; otherwise basic {@link LZFInputStream}
 * (which uses aggregation for underlying streams) is more appropriate
 *<p>
 *<p>
 * Implementation note: much of the code is just copied from {@link LZFInputStream},
 * so care must be taken to keep implementations in sync if there are fixes.
 * 
 * @since 0.8
 */
public class LZFFileInputStream
    extends FileInputStream
{
    /**
     * Object that handles details of buffer recycling
     */
    private final BufferRecycler _recycler;

    /**
     * Flag that indicates if we have already called '_inputStream.close()'
     * (to avoid calling it multiple times)
     */
    protected boolean _inputStreamClosed;
    
    /**
     * Flag that indicates whether we force full reads (reading of as many
     * bytes as requested), or 'optimal' reads (up to as many as available,
     * but at least one). Default is false, meaning that 'optimal' read
     * is used.
     */
    protected boolean _cfgFullReads = false;
        
    /**
     * the current buffer of compressed bytes (from which to decode)
     * */
    private byte[] _inputBuffer;
        
    /**
     * the buffer of uncompressed bytes from which content is read
     * */
    private byte[] _decodedBytes;
        
    /**
     * The current position (next char to output) in the uncompressed bytes buffer.
     * */
    private int _bufferPosition = 0;
    
    /**
     * Length of the current uncompressed bytes buffer
     * */
    private int _bufferLength = 0;

    /**
     * Wrapper object we use to allow decoder to read directly from the
     * stream, without ending in infinite loop...
     */
    private final Wrapper _wrapper;
    
    /*
    ///////////////////////////////////////////////////////////////////////
    // Construction, configuration
    ///////////////////////////////////////////////////////////////////////
     */
    
    public LZFFileInputStream(File file) throws FileNotFoundException
    {
        super(file);
        _recycler = BufferRecycler.instance();
        _inputStreamClosed = false;
        _inputBuffer = _recycler.allocInputBuffer(LZFChunk.MAX_CHUNK_LEN);
        _decodedBytes = _recycler.allocDecodeBuffer(LZFChunk.MAX_CHUNK_LEN);
        _wrapper = new Wrapper();
    }

    public LZFFileInputStream(FileDescriptor fdObj) {
        super(fdObj);
        _recycler = BufferRecycler.instance();
        _inputStreamClosed = false;
        _inputBuffer = _recycler.allocInputBuffer(LZFChunk.MAX_CHUNK_LEN);
        _decodedBytes = _recycler.allocDecodeBuffer(LZFChunk.MAX_CHUNK_LEN);
        _wrapper = new Wrapper();
    }

    public LZFFileInputStream(String name) throws FileNotFoundException {
        super(name);
        _recycler = BufferRecycler.instance();
        _inputStreamClosed = false;
        _inputBuffer = _recycler.allocInputBuffer(LZFChunk.MAX_CHUNK_LEN);
        _decodedBytes = _recycler.allocDecodeBuffer(LZFChunk.MAX_CHUNK_LEN);
        _wrapper = new Wrapper();
    }

    /**
     * Method that can be used define whether reads should be "full" or
     * "optimal": former means that full compressed blocks are read right
     * away as needed, optimal that only smaller chunks are read at a time,
     * more being read as needed.
     */
    public void setUseFullReads(boolean b) {
        _cfgFullReads = b;
    }
    
    /*
    ///////////////////////////////////////////////////////////////////////
    // FileInputStream overrides
    ///////////////////////////////////////////////////////////////////////
     */

    @Override
    public int available()
    {
        // if closed, return -1;
        if (_inputStreamClosed) {
            return -1;
        }
        int left = (_bufferLength - _bufferPosition);
        return (left <= 0) ? 0 : left;
    }

    @Override
    public void close() throws IOException
    {
        _bufferPosition = _bufferLength = 0;
        byte[] buf = _inputBuffer;
        if (buf != null) {
            _inputBuffer = null;
            _recycler.releaseInputBuffer(buf);
        }
        buf = _decodedBytes;
        if (buf != null) {
            _decodedBytes = null;
            _recycler.releaseDecodeBuffer(buf);
        }
        if (!_inputStreamClosed) {
            _inputStreamClosed = true;
            super.close();
        }
    }

    // fine as is: don't override
    // public FileChannel getChannel();

    // final, can't override:
    //public FileDescriptor getFD();

    @Override
    public int read() throws IOException
    {
        if (!readyBuffer()) {
            return -1;
        }
        return _decodedBytes[_bufferPosition++] & 255;
    }

    @Override
    public int read(byte[] b) throws IOException
    {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException
    {
        if (length < 1) {
            checkNotClosed();
            return 0;
        }
        if (!readyBuffer()) {
            return -1;
        }
        // First let's read however much data we happen to have...
        int chunkLength = Math.min(_bufferLength - _bufferPosition, length);
        System.arraycopy(_decodedBytes, _bufferPosition, buffer, offset, chunkLength);
        _bufferPosition += chunkLength;

        if (chunkLength == length || !_cfgFullReads) {
            return chunkLength;
        }
        // Need more data, then
        int totalRead = chunkLength;
        do {
            offset += chunkLength;
            if (!readyBuffer()) {
                break;
            }
            chunkLength = Math.min(_bufferLength - _bufferPosition, (length - totalRead));
            System.arraycopy(_decodedBytes, _bufferPosition, buffer, offset, chunkLength);
            _bufferPosition += chunkLength;
            totalRead += chunkLength;
        } while (totalRead < length);

        return totalRead;
    }

    /**
     * Overridden to just skip at most a single chunk at a time
     */
    @Override
    public long skip(long n) throws IOException
    {
        if (!readyBuffer()) {
            return -1L;
        }
        long left = (_bufferLength - _bufferPosition);
        // either way, just skip whatever we have decoded
        if (left > n) {
            left = (int) n;
        }
        _bufferPosition += left;
        return left;
    }
    
    /*
    ///////////////////////////////////////////////////////////////////////
    // Internal methods
    ///////////////////////////////////////////////////////////////////////
     */

    /**
     * Fill the uncompressed bytes buffer by reading the underlying _inputStream.
     * @throws IOException
     */
    protected boolean readyBuffer() throws IOException
    {
        checkNotClosed();
        if (_bufferPosition < _bufferLength) {
            return true;
        }
        _bufferLength = LZFDecoder.decompressChunk(_wrapper, _inputBuffer, _decodedBytes);
        if (_bufferLength < 0) {
            return false;
        }
        _bufferPosition = 0;
        return (_bufferPosition < _bufferLength);
    }

    protected final int readRaw(byte[] buffer, int offset, int length) throws IOException
    {
        return super.read(buffer, offset, length);
    }

    protected void checkNotClosed() throws IOException
    {
        if (_inputStreamClosed) {
            throw new IOException(getClass().getName()+" already closed");
        }
    }
    
    /*
    ///////////////////////////////////////////////////////////////////////
    // Helper class(es)
    ///////////////////////////////////////////////////////////////////////
     */


    /**
     * This simple wrapper is needed to re-route read calls so that they will
     * use "raw" reads
     */
    private final class Wrapper extends InputStream
    {
        @Override
        public int read() throws IOException {
            throw new UnsupportedOperationException();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException
        {
            return readRaw(buffer, offset, length);
        }
        
    }
}
