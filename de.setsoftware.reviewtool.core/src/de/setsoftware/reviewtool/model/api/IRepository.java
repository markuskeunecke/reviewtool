package de.setsoftware.reviewtool.model.api;

import java.io.Serializable;

/**
 * A source code management repository.
 */
public interface IRepository extends Serializable {

    /**
     * Returns an identifier that is unique amongst all known repositories.
     */
    public abstract String getId();

    /**
     * Converts the string representation of a repository revision into a {@link IRepoRevision}.
     *
     * @param revisionId The revision to convert.
     * @return A {@link IRepoRevision} object or {@code null} if ID could not be converted.
     */
    public abstract IRepoRevision<?> toRevision(String revisionId);

    /**
     * Returns the contents of some revisioned file in the repository.
     * @param path The path to the file.
     * @param revision The revision of the file. This is also used as peg revision of the path passed above.
     * @return The file contents as a byte array.
     * @throws Exception if an error occurs.
     */
    public abstract byte[] getFileContents(String path, IRepoRevision<?> revision) throws Exception;

    /**
     * Returns the associated file history graph.
     */
    public abstract IFileHistoryGraph getFileHistoryGraph();
}
