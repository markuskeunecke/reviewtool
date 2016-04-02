package de.setsoftware.reviewtool.model.changestructure;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;

/**
 * Allows the transformation from position in the form (line,column) to
 * "number of characters since file start" and caches relevant information.
 */
public class PositionLookupTable {

    private final List<Integer> charCountAtEndOfLine = new ArrayList<>();

    private PositionLookupTable() {
    }

    /**
     * Creates a lookup table for the contents from the given file.
     */
    public static PositionLookupTable create(IFile file)
            throws IOException, CoreException {
        final InputStream stream = file.getContents();
        try {
            final Reader r = new InputStreamReader(stream, file.getCharset());
            return create(r);
        } finally {
            stream.close();
        }
    }

    /**
     * Creates a lookup table for the contents from the given reader.
     */
    static PositionLookupTable create(Reader reader) throws IOException {
        final PositionLookupTable ret = new PositionLookupTable();
        int ch;
        int charCount = 0;
        ret.charCountAtEndOfLine.add(0);
        while ((ch = reader.read()) >= 0) {
            charCount++;
            if (ch == '\n') {
                ret.charCountAtEndOfLine.add(charCount);
            }
        }
        return ret;
    }

    public int getCharsSinceFileStart(PositionInText pos) {
        return this.charCountAtEndOfLine.get(pos.getLine() - 1) + pos.getColumn();
    }

}
