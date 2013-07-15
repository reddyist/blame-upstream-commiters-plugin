package hudson.plugins.blame_upstream_commiters;

import com.google.common.base.Function;
import com.google.common.collect.Collections2;
import hudson.Extension;
import hudson.Launcher;
import hudson.model.*;
import hudson.tasks.ArtifactArchiver;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.MailSender;
import hudson.tasks.Mailer;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;

import java.io.File;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.apache.tools.ant.types.selectors.SelectorUtils;
import org.kohsuke.stapler.DataBoundConstructor;

import hudson.model.CauseAction;
import java.util.HashSet;
import hudson.model.Cause;
import hudson.model.Cause.UpstreamCause;
import hudson.model.Action;
import hudson.model.Hudson;
import jenkins.model.Jenkins;
import hudson.model.FreeStyleProject;
import hudson.model.FreeStyleBuild;



@SuppressWarnings({ "unchecked" })
public class BlameUpstreamCommitersPublisher extends Notifier {
	//public static final DescriptorImpl DESCRIPTOR = new DescriptorImpl();
	protected static final Logger LOGGER = Logger.getLogger(Mailer.class.getName());
	
	public boolean sendToIndividuals = false;
	
	@DataBoundConstructor
	public BlameUpstreamCommitersPublisher()
	{
	}
	
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}
	
	@Override
	public boolean needsToRunAfterFinalized() {
        return true;
    }
	
	@Override
    public boolean perform(AbstractBuild<?,?> build, Launcher launcher, BuildListener listener) throws InterruptedException {
		if (build.getResult() != Result.SUCCESS)
		{
			Set<AbstractProject> upstreamProjects = getUpstreamProjects(build);
			if (!upstreamProjects.isEmpty()) {
                
                Collection<String> namesCollection = Collections2.transform(upstreamProjects, new Function<AbstractProject, String>() {
                    public String apply(AbstractProject from) {
                        return from.getName();
                    }
                });
                listener.getLogger().println("Upstream projects changes detected. Mailing upstream committers in the following projects:");
                listener.getLogger().println(StringUtils.join(namesCollection, ","));
                
                return new MailSender( "", false, sendToIndividuals, "UTF-8", upstreamProjects ) {
                    /** Check whether a path (/-separated) will be archived. */
                    @Override
                    public boolean artifactMatches( String path, AbstractBuild<?, ?> build ) {
                        ArtifactArchiver aa = build.getProject().getPublishersList().get( ArtifactArchiver.class );
                        if ( aa == null ) {
                            LOGGER.finer( "No ArtifactArchiver found" );
                            return false;
                        }
                        String artifacts = aa.getArtifacts();
                        for ( String include : artifacts.split( "[, ]+" ) ) {
                            String pattern = include.replace( File.separatorChar, '/' );
                            if ( pattern.endsWith( "/" ) ) {
                                pattern += "**";
                            }
                            if ( SelectorUtils.matchPath( pattern, path ) ) {
                                LOGGER.log( Level.FINER, "DescriptorImpl.artifactMatches true for {0} against {1}", new Object[]{path, pattern} );
                                return true;
                            }
                        }
                        LOGGER.log( Level.FINER, "DescriptorImpl.artifactMatches for {0} matched none of {1}", new Object[]{path, artifacts} );
                        return false;
                    }
                }.execute(build,listener);
			}
		}
		return true;
	}

    private void getUpstreamProjectRecursive(UpstreamCause cause, ArrayList upstreamset) {
        upstreamset.add(cause.getUpstreamProject());
        
        String uprojectname = cause.getUpstreamProject();
        int bnum = cause.getUpstreamBuild();
        Jenkins instance = Hudson.getInstance();
        FreeStyleProject uproject = (FreeStyleProject) instance.getItem(uprojectname);
        FreeStyleBuild ubuild = (FreeStyleBuild) uproject.getBuildByNumber(bnum);
        
        CauseAction uaction = null;
        Object element = null;
        Iterator<Action> actions = ubuild.getActions().iterator();
        while (actions.hasNext()) {
            element = actions.next();
            if (element.toString().contains("CauseAction")) {
                uaction = (CauseAction) element;
                break;
            }
        }
        
        if (uaction != null) {
            Iterator<Cause> ucauses = uaction.getCauses().iterator();
            while (ucauses.hasNext()) {
                element = ucauses.next();
                if (element.getClass().getName().contains("UpstreamCause")) {
                    getUpstreamProjectRecursive((UpstreamCause) element, upstreamset);
                }
            }        
        }
    }
    
    private Set<AbstractProject> getUpstreamProjects(AbstractBuild<?, ?> build) {
        //return build.getUpstreamBuilds().keySet();
        CauseAction cause = null;
        List<Action> actions = build.getActions();
        for (int i=0; i<actions.size(); i++) {
            if (actions.get(i).toString().contains("CauseAction")) {
                cause = (CauseAction) actions.get(i);
                break;
            }
        }
        ArrayList<String> upstreamset = new ArrayList<String>();
        for (int i=0; i<cause.getCauses().size(); i++ ) {
            if (cause.getCauses().get(i).getClass().getName().contains("UpstreamCause")) {
                getUpstreamProjectRecursive((UpstreamCause) cause.getCauses().get(i), upstreamset);
            }
        }
        HashSet uprojects = new HashSet();
        for (int p=0; p<upstreamset.size(); p++) {
            Jenkins instance = Hudson.getInstance();
            uprojects.add(instance.getItem(upstreamset.get(p)));
        }
        return uprojects;
}

    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        public DescriptorImpl() {
            super(BlameUpstreamCommitersPublisher.class);
        }

        public String getDisplayName() {
            return "Mail upstream committers when the build fails";
        }

        public boolean isApplicable(Class<? extends AbstractProject> jobType) {
            return true;
        }
    }
}
