/*
Copyright (C) 2013 Hoiio Pte Ltd (http://www.hoiio.com)

Permission is hereby granted, free of charge, to any person
obtaining a copy of this software and associated documentation
files (the "Software"), to deal in the Software without
restriction, including without limitation the rights to use,
copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the
Software is furnished to do so, subject to the following
conditions:

The above copyright notice and this permission notice shall be
included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES
OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NON INFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
OTHER DEALINGS IN THE SOFTWARE.
*/

package ru.smsc.jenkins.plugin;

import hudson.Extension;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.tasks.BuildStepDescriptor;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Notifier;
import hudson.tasks.Publisher;
import hudson.util.FormValidation;

import net.sf.json.JSONObject;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.StaplerResponse;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.PostMethod;

import javax.servlet.ServletException;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.io.PrintStream;

public class SMSNotification extends Notifier {

    private final String recipients;

    final int MAX_SMS_LENGTH = 150;

    @DataBoundConstructor
    public SMSNotification(String recipients) {
        this.recipients = recipients;
    }

    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {
        // build.getResult() == Result.FAILURE, Result.UNSTABLE
        String username = getDescriptor().getUsername();
        String password = getDescriptor().getPassword();

        if (isEmpty(recipients)) {
            listener.error("No recipients");
            return true;
        }

        if (isEmpty(username) || isEmpty(password)) {
            listener.error("SMSC credentials not configured; cannot send SMS notification");
            return true;
        }

        String message = makeMessage(build);
        if (message.length() > MAX_SMS_LENGTH) {
            message = message.substring(0, MAX_SMS_LENGTH - 5) + "...";
        }

        List<String> receiverList = new ArrayList<String>();

        // Remove all spaces
        String recipientStr = recipients.trim().replaceAll("\\s","");
        receiverList.addAll(Arrays.asList(recipientStr.split(",")));

        final PrintStream stream = listener.getLogger();

        for(String rec: receiverList) {
            stream.println(String.format("Start send message %s on %s", message, rec));
            try {
                send(username, password, rec, message);
            } catch (Exception e) {
                listener.error("Failed to send SMS notification: " + e.getMessage());
                //build.setResult(Result.UNSTABLE);
            }
        }

        return true;
    }

    public String makeMessage(AbstractBuild build) {
        Result result = build.getResult();
        // "Jenkins Build: " + build.getProject().getDisplayName() + " at " + ;
        //  message.setUrl(String.format("%s%s", baseUrl, build.getUrl()), "Go to build");
        String msg = String.format(
            "Build #%d of %s is %s on %s",
            build.getNumber(),                   // Number
            build.getProject().getName(),        // Project
            result.toString(),                   // Status
            getDateString(build.getTime())       // Date
        );
        return msg;
    }

    public void send(String username, String password, String rec, String message) {
        HttpClient client = new HttpClient();
        PostMethod post = new PostMethod(String.format(
            "https://smsc.ru/sys/send.php"
        ));
        post.setRequestBody(new NameValuePair[]{
            new NameValuePair("login", username),
            new NameValuePair("psw", password),
            new NameValuePair("phones", rec),
            new NameValuePair("mes", message),
            new NameValuePair("charset", "utf-8")
        });
        try {
            client.executeMethod(post);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Overridden for better type safety.
    // If your plugin doesn't really define any property on Descriptor,
    // you don't have to do this.
    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl)super.getDescriptor();
    }

    public String getRecipients() {
        return recipients;
    }

    /**
     * Descriptor for {@link SMSNotification}. Used as a singleton.
     * The class is marked as public so that it can be accessed from views.
     *
     * <p>
     * See <tt>src/main/resources/hudson/plugin/hello_world/SMSNotification/*.jelly</tt>
     * for the actual HTML fragment for the configuration screen.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Publisher> {

        private String username;
        private String password;

        public DescriptorImpl() {
            super(SMSNotification.class);
            load();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            return true;
        }

        @Override
        public String getDisplayName() {
            return "SMS Notification";
        }

        @Override
        public boolean configure(StaplerRequest req, JSONObject formData) throws FormException {
            username = formData.getString("username");
            password = formData.getString("password");
            save();
            return super.configure(req,formData);
        }

        public String getUsername() {
            return username;
        }

        public void setUsername(String username) {
            this.username = username;
        }

        public String getPassword() {
            return password;
        }

        public void setPassword(String password) {
            this.password = password;
        }

        public FormValidation doNumberCheck(@QueryParameter String param) throws IOException, ServletException {
            if (param == null || param.trim().length() == 0) {
                return FormValidation.warning("You must fill recipients' numbers!");
            }

            param = param.trim().replaceAll("\\s","");
            for (String p: param.split(",")) {
                if (!PhoneNumberValidator.validatePhoneNumber(p)) {
                    return FormValidation.error("Formats of some recipients' numbers are invalid.");
                }
            }

            return FormValidation.ok();
        }
    }

    public BuildStepMonitor getRequiredMonitorService() {
        return BuildStepMonitor.NONE;
    }

    private boolean isEmpty(String s) {
        return s == null || s.trim().length() == 0;
    }

    private String getDateString(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }
}

