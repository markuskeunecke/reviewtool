package de.setsoftware.reviewtool.model.api;

import java.io.File;
import java.util.List;

/**
 * Interface for strategies to determine the changes for a ticket, separated into commits.
 */
public interface IChangeSource extends IRepositoryProvider {

    /**
     * Returns the ID of this change source.
     * Implementation note: To make the ID unique, use the package name.
     */
    public abstract String getId();

    /**
     * Returns all repository changes (that are relevant for the review tool) for the ticket with the given key.
     */
    public abstract IChangeData getRepositoryChanges(String key, IChangeSourceUi ui) throws ChangeSourceException;

    /**
     * Collects all local changes (that are relevant for the review tool) and updates the local file history graph.
     *
     * @param relevantPaths The files to consider while searching for modifications. If {@code null},
     *      the whole working copy is considered.
     */
    public abstract void analyzeLocalChanges(List<File> relevantPaths) throws ChangeSourceException;

    /**
     * Notifies the change source that a project has been added.
     *
     * @param projectRoot The root directory of the project.
     */
    public abstract void addProject(final File projectRoot) throws ChangeSourceException;

    /**
     * Notifies the change source that a project has been removed.
     *
     * @param projectRoot The root directory of the project.
     */
    public abstract void removeProject(final File projectRoot) throws ChangeSourceException;
}
