package de.setsoftware.reviewtool.changesources.svn;

import java.util.Date;
import java.util.Map;

import de.setsoftware.reviewtool.model.api.IRepoRevision;
import de.setsoftware.reviewtool.model.changestructure.ChangestructureFactory;

/**
 * Encapsulates a Subversion revision with associated information about the repository, the log message, the commit
 * date, the commit author, and the paths changed.
 */
final class SvnRepoRevision extends AbstractSvnRevision {
    private final SvnRepo repository;
    private final CachedLogEntry logEntry;

    /**
     * Constructor.
     * @param repository The associated repository.
     * @param logEntry The log entry.
     */
    SvnRepoRevision(final SvnRepo repository, final CachedLogEntry logEntry) {
        this.repository = repository;
        this.logEntry = logEntry;
    }

    @Override
    public SvnRepo getRepository() {
        return this.repository;
    }

    @Override
    public long getRevisionNumber() {
        return this.logEntry.getRevision();
    }

    @Override
    public String getRevisionString() {
        return Long.toString(this.logEntry.getRevision());
    }

    @Override
    public IRepoRevision toRevision() {
        return ChangestructureFactory.createRepoRevision(this.getRevisionNumber(), this.repository);
    }

    @Override
    public Date getDate() {
        return this.logEntry.getDate();
    }

    @Override
    public String getAuthor() {
        return this.logEntry.getAuthor();
    }

    @Override
    public String getMessage() {
        return this.logEntry.getMessage();
    }

    @Override
    public Map<String, CachedLogEntryPath> getChangedPaths() {
        return this.logEntry.getChangedPaths();
    }

    @Override
    public String toPrettyString() {
        final StringBuilder sb = new StringBuilder();
        final String message = this.getMessage();
        if (!message.isEmpty()) {
            sb.append(message);
            sb.append(" ");
        }
        sb.append(String.format(
                "(Rev. %s, %s)",
                this.getRevisionString(),
                this.getAuthor()));
        return sb.toString();
    }

    @Override
    public String toString() {
        return this.repository.toString() + "@" + this.logEntry.toString();
    }
}