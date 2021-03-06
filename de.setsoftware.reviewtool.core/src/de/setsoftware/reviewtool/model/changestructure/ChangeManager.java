package de.setsoftware.reviewtool.model.changestructure;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.IResourceChangeEvent;
import org.eclipse.core.resources.IResourceChangeListener;
import org.eclipse.core.resources.IResourceDelta;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.IJobFunction;
import org.eclipse.core.runtime.jobs.ISchedulingRule;
import org.eclipse.core.runtime.jobs.Job;

import de.setsoftware.reviewtool.base.Logger;
import de.setsoftware.reviewtool.base.WeakListeners;
import de.setsoftware.reviewtool.model.api.ChangeSourceException;
import de.setsoftware.reviewtool.model.api.IChangeSource;
import de.setsoftware.reviewtool.telemetry.Telemetry;

/**
 * Helps to manage changes used while reviewing or fixing code.
 */
public final class ChangeManager {

    /**
     * Singleton scheduling rule preventing two update jobs to run concurrently.
     */
    private static class MutexRule implements ISchedulingRule {

        private static final MutexRule RULE_INSTANCE = new MutexRule();

        private MutexRule() {
        }

        static MutexRule getInstance() {
            return RULE_INSTANCE;
        }

        @Override
        public boolean isConflicting(final ISchedulingRule rule) {
            return rule == this;
        }

        @Override
        public boolean contains(final ISchedulingRule rule) {
            return rule == this;
        }
    }

    /**
     * Handles resource changes.
     */
    private final class ResourceChangeListener implements IResourceChangeListener {
        private boolean logged;

        @Override
        public void resourceChanged(final IResourceChangeEvent event) {
            this.logged = false;
            final List<File> projectsAdded = new ArrayList<>();
            final List<File> projectsRemoved = new ArrayList<>();
            final List<File> filesChanged = new ArrayList<>();

            if (event.getType() == IResourceChangeEvent.POST_CHANGE) {
                try {
                    this.handleResourceDelta(event.getDelta(), projectsAdded, projectsRemoved, filesChanged);
                } catch (final Exception e) {
                    Logger.error("Error while processing local changes", e);
                }
            } else if (event.getType() == IResourceChangeEvent.PRE_DELETE) {
                final IPath location = event.getResource().getLocation();
                assert location != null;
                projectsRemoved.add(location.toFile());
            }

            if (!projectsAdded.isEmpty() || !projectsRemoved.isEmpty() || !filesChanged.isEmpty()) {
                final Job job = Job.create("Processing local changes",
                        new IJobFunction() {
                            @Override
                            public IStatus run(final IProgressMonitor monitor) {
                                ChangeManager.this.processLocalChanges(projectsAdded, projectsRemoved, filesChanged);
                                return Status.OK_STATUS;
                            }
                        });
                job.setRule(MutexRule.getInstance());
                job.schedule();
            }
        }

        private void handleResourceDelta(
                final IResourceDelta delta,
                final List<File> projectsAdded,
                final List<File> projectsRemoved,
                final List<File> filesChanged) {

            final IResource resource = delta.getResource();
            if (resource.isDerived()) {
                return;
            }

            final IPath location = resource.getLocation();
            if (location == null) {
                return;
            }

            if (resource.getType() == IResource.FILE) {
                if (!this.logged && (delta.getFlags() & IResourceDelta.CONTENT) != 0) {
                    Telemetry.event("fileChanged")
                        .param("path", delta.getFullPath())
                        .param("kind", delta.getKind())
                        .log();
                    this.logged = true;
                }

                if (delta.getKind() != IResourceDelta.CHANGED
                        || (delta.getFlags() & (IResourceDelta.CONTENT | IResourceDelta.REPLACED)) != 0) {
                    final File filePath = location.toFile();
                    filesChanged.add(filePath);
                }
            } else {
                if (resource.getType() == IResource.PROJECT && delta.getKind() == IResourceDelta.ADDED) {
                    projectsAdded.add(location.toFile());
                }

                for (final IResourceDelta d : delta.getAffectedChildren()) {
                    this.handleResourceDelta(d, projectsAdded, projectsRemoved, filesChanged);
                }
            }
        }
    }

    private final Set<File> projectDirs;
    private final AtomicReference<IChangeSource> changeSourceRef;
    private final WeakListeners<IChangeManagerListener> changeManagerListeners = new WeakListeners<>();

    /**
     * Constructor.
     *
     * @param workspaceRequired If {@code true}, the workspace is required to exist (and an exception of type
     * {@link IllegalMonitorStateException} is thrown if this is not the case). Use {@code false} only for unit tests!
     */
    public ChangeManager(final boolean workspaceRequired) {
        this.projectDirs = new LinkedHashSet<>();
        this.changeSourceRef = new AtomicReference<>(null);

        IWorkspace root = null;
        try {
            root = ResourcesPlugin.getWorkspace();
        } catch (final IllegalStateException e) {
            if (workspaceRequired) {
                throw e;
            }
        }

        if (root != null) {
            final IResourceChangeListener changeListener = new ResourceChangeListener();
            ResourcesPlugin.getWorkspace().addResourceChangeListener(
                    changeListener,
                    IResourceChangeEvent.PRE_DELETE | IResourceChangeEvent.POST_CHANGE);

            for (final IProject project : root.getRoot().getProjects()) {
                final IPath location = project.getLocation();
                if (location != null) {
                    this.projectDirs.add(location.toFile());
                }
            }
        }
    }

    /**
     * Returns the change source.
     */
    public IChangeSource getChangeSource() {
        return this.changeSourceRef.get();
    }

    /**
     * Sets the change source.
     * If the new change source is valid, projects are added to it and analysis of local changes is requested.
     * @param changeSource The change source to set.
     */
    public void setChangeSource(final IChangeSource changeSource) {
        this.changeSourceRef.set(changeSource);
        if (changeSource != null) {
            final Job job = Job.create("Initializing change source " + changeSource.getClass().getSimpleName(),
                    new IJobFunction() {
                        @Override
                        public IStatus run(final IProgressMonitor monitor) {
                            ChangeManager.this.addProjectsAndCollectLocalChanges();
                            return Status.OK_STATUS;
                        }
                    });
            job.schedule();
        }
    }

    /**
     * Adds a listener to be notified about updates.
     *
     * @param changeManagerListener The listener to add.
     */
    public synchronized void addListener(final IChangeManagerListener changeManagerListener) {
        this.changeManagerListeners.add(changeManagerListener);
    }

    /**
     * Analyzes local changes and combines them with the remote changes managed by this object.
     * Notifies listeners about the update.
     *
     * @param changeSource The change source to use.
     * @param filesToAnalyze Files to analyze. If {@code null}, all local files are checked for local modifications.
     */
    private void analyzeLocalChanges(final IChangeSource changeSource, final List<File> filesToAnalyze)
            throws ChangeSourceException {
        changeSource.analyzeLocalChanges(filesToAnalyze);
        this.changeManagerListeners.notifyListeners(listener -> listener.localChangeInfoUpdated(this));
    }

    /**
     * Initializes a freshly set {@link IChangeSource} by adding projects and analyzing local changes.
     */
    private synchronized void addProjectsAndCollectLocalChanges() {
        final IChangeSource changeSource = this.changeSourceRef.get();
        if (changeSource != null) {
            try {
                for (final File projectRoot : this.projectDirs) {
                    changeSource.addProject(projectRoot);
                }
                this.analyzeLocalChanges(changeSource, null);
            } catch (final ChangeSourceException e) {
                //if there is a problem while determining local changes, ignore them
                Logger.warn("Problem while initially collecting local changes", e);
            }
        }
    }

    /**
     * Updates an existing {@link IChangeSource} after local changes have been detected.
     * @param projectsAdded Projects added in the meantime.
     * @param projectsRemoved Projects removed in the meantime.
     * @param filesChanged Files changed in the meantime.
     */
    private synchronized void processLocalChanges(
            final List<File> projectsAdded,
            final List<File> projectsRemoved,
            final List<File> filesChanged) {

        final IChangeSource changeSource = this.changeSourceRef.get();
        if (changeSource != null) {
            try {
                for (final File project : projectsAdded) {
                    this.projectDirs.add(project);
                    changeSource.addProject(project);
                    Logger.info("Adding project " + project);
                }
                for (final File project : projectsRemoved) {
                    this.projectDirs.remove(project);
                    changeSource.removeProject(project);
                    Logger.info("Removing project " + project);
                }
                this.analyzeLocalChanges(changeSource, filesChanged);
            } catch (final ChangeSourceException e) {
                //if there is a problem while determining local changes, ignore them
                Logger.warn("Problem while processing local changes incrementally", e);
            }
        }
    }
}
