package vn.penta;


import java.net.URL;
import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;

public class AppUtil {
	private static Logger logger = Logger.getLogger(AppUtil.class.getName());
	public static final String secretKey = "#PLease pay for license!";
	public static String APP_GLOBAL_DATA_FILE = "GlobalData.dat"; // This value
																	// is set in
																	// class
																	// AppStarter
	public static final String ICON_IMAGE_LOC = "/resources/icon.png";
	public static final String MAIL_CONTENT_LOC = "/resources/mail_content.html";
	private static final SimpleDateFormat DATE_TIME_FORMAT = new SimpleDateFormat("dd-MM-yyyy hh:mm:ss a");
	private static final SimpleDateFormat DATE_FORMAT = new SimpleDateFormat("dd-MM-yyyy");
	public static SimpleDateFormat dateFormat = new SimpleDateFormat("dd/MM/yyyy");
	// 2013-01-30T00:00:00
	public static SimpleDateFormat isoDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");
	public static NumberFormat nf = NumberFormat.getInstance(new Locale("en", "US"));
	public static String LABEL_PRINT_PATH = "";
	public static String WEB_PROTOCOL = "";
	public static String WEBSERVICE_URL = "";
	public static String WEBSERVICE_HOST = "";
	public static String WEBSERVICE_PORT = "";
	public static String SQL_DB_CONNECTION = "";

	public static long COMPANY_ID = 20155;


	public static final String serviceCompanyName = "Portal_CompanyService";
	public static final String serviceUserName = "Portal_UserService";
	public static final String serviceUserGroupName = "Portal_UserGroupService";


	public static String formatDateTimeString(Date date) {
		return DATE_TIME_FORMAT.format(date);
	}

	public static String formatDateTimeString(Long time) {
		return DATE_TIME_FORMAT.format(new Date(time));
	}

	public static String getDateString(Date date) {
		return DATE_FORMAT.format(date);
	}

	public static boolean validateEmailAddress(String emailID) {
		String regex = "^[_A-Za-z0-9-\\+]+(\\.[_A-Za-z0-9-]+)*@" + "[A-Za-z0-9-]+(\\.[A-Za-z0-9]+)*(\\.[A-Za-z]{2,})$";
		Pattern pattern = Pattern.compile(regex);
		return pattern.matcher(emailID).matches();
	}



	
	public static URL getLiferayServiceURL(String remoteUser, String password, String serviceName, boolean authenticate)
			throws Exception {

		// Unauthenticated url
		String url = AppUtil.WEBSERVICE_URL + "/api/axis/" + serviceName;

		// Authenticated url
		if (authenticate) {
			url = AppUtil.WEB_PROTOCOL + "://" + remoteUser + ":" + password + "@" + AppUtil.WEBSERVICE_HOST + ":"
					+ AppUtil.WEBSERVICE_PORT + "/api/axis/" + serviceName;
		}
		System.out.println(url);
		return new URL(url);
	}

	


	public static ClientHttpRequestFactory getClientHttpRequestFactory() {
		int timeout = 1000;
		HttpComponentsClientHttpRequestFactory clientHttpRequestFactory = new HttpComponentsClientHttpRequestFactory();
		clientHttpRequestFactory.setConnectTimeout(timeout);
		return clientHttpRequestFactory;
	}


	

}
