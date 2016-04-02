package de.setsoftware.reviewtool.model.changestructure;

/**
 * A position in a text file, denoted by line and column number.
 */
public class PositionInText {

    private final int line;
    private final int column;

    public PositionInText(int line, int column) {
        this.line = line;
        this.column = column;
    }

    @Override
    public String toString() {
        return this.line + ":" + this.column;
    }

    public int getLine() {
        return this.line;
    }

    public int getColumn() {
        return this.column;
    }

}
