package de.m4lik.burningseries.hoster;

import android.os.SystemClock;
import android.util.Log;

import com.crashlytics.android.Crashlytics;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class PowerWatch extends Hoster {

    protected static final Pattern filenamePattern;
    protected static final Pattern hashPattern;
    protected static final Pattern urlPattern;

    static {
        filenamePattern = Pattern.compile("<input type=\"hidden\" name=\"fname\" value=\"([0-9a-zA-Z-._ ]+)\">");
        hashPattern = Pattern.compile("<input type=\"hidden\" name=\"hash\" value=\"([a-zA-Z0-9-]+)\">");
        urlPattern = Pattern.compile("sources: \\[\\{file:\"(http://([a-zA-Z0-9/.]+))\",label:");
    }

    public String get(String videoID) {
        String fullURL = "http://powerwatch.pw/" + videoID;
        try {
            CharSequence GetRequest = GetRequestString(fullURL);
            if (GetRequest.equals("SOCKET_TIMEOUT")) {
                //Oh damnit. Took too long...
                return "1";
            }

            Map<String, String> dataArgs = new HashMap<>(7);

            Matcher matcher = filenamePattern.matcher(GetRequest);
            Matcher matcher2 = hashPattern.matcher(GetRequest);

            if (!matcher.find() || !matcher2.find()) {
                //The Skies are clear. Unusual.
                return "2";
            }

            dataArgs.put("fname", matcher.group(1));
            dataArgs.put("hash", matcher2.group(1));
            dataArgs.put("op", "download1");
            dataArgs.put("usr_login", "");
            dataArgs.put("id", videoID);
            dataArgs.put("referer", "");
            dataArgs.put("imhuman", "");
            Map<String, String> refererArgs = new HashMap<>(1);
            refererArgs.put("Referer", fullURL);
            SystemClock.sleep(5000);
            try {
                Matcher matcher3 = urlPattern.matcher(PostRequestString(fullURL, dataArgs, refererArgs));
                if (matcher3.find()) {
                    return matcher3.group(1);
                }
                Crashlytics.log(Log.ERROR, "HOSTER", "No video URL found (" + fullURL + ")");
                //We ain't found shit, Sir!
                return "3";
            } catch (Exception e) {
                e.printStackTrace();
                Crashlytics.log(Log.ERROR, "HOSTER", "Error while fetching video URL (" + fullURL + ")");
                Crashlytics.logException(e);
                return "4";
            }
        } catch (Exception e) {
            e.printStackTrace();
            Crashlytics.log(Log.ERROR, "HOSTER", "Error while getting video page (" + fullURL + ")");
            Crashlytics.logException(e);
            //Whoops... The thing broke.
            return "5";
        }
    }
}
