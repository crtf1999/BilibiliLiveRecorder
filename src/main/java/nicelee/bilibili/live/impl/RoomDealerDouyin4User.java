package nicelee.bilibili.live.impl;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.json.JSONArray;
import org.json.JSONObject;

import nicelee.bilibili.live.RoomDealer;
import nicelee.bilibili.live.domain.RoomInfo;
import nicelee.bilibili.util.HttpCookies;
import nicelee.bilibili.util.Logger;

public class RoomDealerDouyin4User extends RoomDealer {

	final public static String liver = "douyin";

	final static Pattern pJson = Pattern.compile("<script id=\"RENDER_DATA\".*?>(.*?%7D)</script>");
	final static Pattern pShortId = Pattern.compile("live.douyin.com/([0-9]+)");
	final static Pattern pWebcastId = Pattern.compile("webcast.amemv.com/webcast/reflow/([0-9]+)");

	final static Pattern pJsonMobile = Pattern.compile("<script>window.__INIT_PROPS__ *= *(.*?)</script>");
	@Override
	public String getType() {
		return ".flv";
	}

	/**
	 * @param shortId
	 * @return
	 */
	@Override
	public RoomInfo getRoomInfo(String shortId) {
		if (shortId.startsWith("https://v.douyin.com")) {
			try {
				URL url = new URL(shortId);
				HttpURLConnection conn = (HttpURLConnection) url.openConnection();
				conn.setInstanceFollowRedirects(false);
				conn.connect();

				String location = conn.getHeaderField("Location");
				Logger.println(location);
				if(location.startsWith("https://www.iesdouyin.com")) {
					// https://www.iesdouyin.com/share/live/6825590732829657870?anchor_id=59592712724
					url = new URL(location);
					conn = (HttpURLConnection) url.openConnection();
					conn.setInstanceFollowRedirects(false);
					conn.setRequestProperty("User-Agent",
							"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:86.0) Gecko/20100101 Firefox/86.0");
					conn.connect();
					location = conn.getHeaderField("Location");
					Logger.println(location);
				}
				if (location.startsWith("https://webcast.amemv.com")) {
					// https://webcast.amemv.com/webcast/reflow/6825590732829657870
					url = new URL(location);
					conn = (HttpURLConnection) url.openConnection();
					conn.setInstanceFollowRedirects(false);
					conn.setRequestProperty("User-Agent",
							"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:86.0) Gecko/20100101 Firefox/86.0");
					conn.connect();
					if (conn.getResponseCode() >= 300 && conn.getResponseCode() < 400) {
						location = conn.getHeaderField("Location");
						Logger.println(location);
					} else {
						Matcher matcher = pWebcastId.matcher(location);
						matcher.find();
						String webcastId = matcher.group(1);
						shortId = getLiveDataByWebcastId(webcastId).getJSONObject("data").getJSONObject("room").getJSONObject("owner")
								.getString("web_rid");
					}

				}
				if (location != null) {
					// e.g. https://live.douyin.com/4795593332 ...
					Matcher matcher = pShortId.matcher(location);
					if (matcher.find())
						shortId = matcher.group(1);
				}
			} catch (IOException e) {
				System.err.println("不支持这种短链接的解析!!");
				throw new RuntimeException(e);
			}
		}

		RoomInfo roomInfo = new RoomInfo();
		roomInfo.setShortId(shortId);
		try {
			String roomId = shortId;

			String jsonData = util.getContent("https://live.douyin.com/webcast/room/web/enter/?aid=6383&live_id=1&device_platform=web&language=zh-CN&enter_from=web_live&cookie_enabled=true&screen_width=1920&screen_height=1080&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=109.0.0.0&web_rid=" + roomId + "&enter_source=&Room-Enter-User-Login-Ab=1&is_need_double_stream=false", getPCHeader(), HttpCookies.convertCookies(cookie));
			if (jsonData == null || jsonData.trim().isEmpty() || jsonData.contains("系统繁忙，请稍后再试")) {
				Logger.println("cookie失效: " + jsonData);
				throw new IllegalArgumentException("cookie失效");
			}
			String json_str = URLDecoder.decode(jsonData, "UTF-8");
			Logger.println(json_str);
			JSONObject json = new JSONObject(json_str);
			//有时结构会发生变化
			boolean existsData = json.has("data") &&
					json.getJSONObject("data").has("data");
			if (!existsData) {
				Logger.println("json格式异常: " + json);
				throw new IllegalArgumentException("json格式异常");
			}
			JSONObject info = json.getJSONObject("data")
					.getJSONArray("data")
					.getJSONObject(0);
			JSONObject anchor = json.getJSONObject("data")
					.getJSONObject("user");
			JSONObject room = info;
			JSONObject stream_url = room.optJSONObject("stream_url");

			roomInfo.setUserName(anchor.getString("nickname"));
			roomInfo.setRoomId(roomId);
			roomInfo.setUserId(anchor.optLong("id_str"));
			roomInfo.setTitle(room.getString("title"));
			roomInfo.setDescription(anchor.getString("nickname") + " 的直播间");
			if (stream_url == null) {
				if(room.getInt("status") == 2) {
					// 说明仍然在直播，只是PC端不让播放
					Logger.println("当前直播仅支持移动端播放");
					String webcastId = room.getString("id_str");
					roomInfo.setRemark(webcastId);
					roomInfo.setLiveStatus(1);
					String html;
					Matcher matcher;
					html = util.getContent("https://webcast.amemv.com/webcast/reflow/" + webcastId, getMobileHeader());

					matcher = pJsonMobile.matcher(html);
					matcher.find();
					json = new JSONObject(matcher.group(1)).getJSONObject("/webcast/reflow/:id")
							.getJSONObject("room");
					stream_url = json.getJSONObject("stream_url");
					JSONArray jArray = stream_url.getJSONObject("live_core_sdk_data").getJSONObject("pull_data")
							.getJSONObject("options").getJSONArray("qualities");
					int qualityLen = jArray.length();
					String[] qn = new String[qualityLen];
					String[] qnDesc = new String[qualityLen];
					for (int i = 0; i < qualityLen; i++) {
						JSONObject obj = jArray.getJSONObject(i);
						int level = obj.optInt("level");
						qn[i] = "" + i;
						qnDesc[qualityLen - level] = obj.getString("name");
					}
					roomInfo.setAcceptQuality(qn);
					roomInfo.setAcceptQualityDesc(qnDesc);
				}else {
					roomInfo.setLiveStatus(0);
				}
			} else {
				roomInfo.setLiveStatus(1);
				JSONArray flv_sources = stream_url.getJSONObject("live_core_sdk_data").getJSONObject("pull_data")
						.getJSONObject("options").getJSONArray("qualities");
				int flv_sources_len = flv_sources.length();
				String[] qn = new String[flv_sources_len];
				String[] qnDesc = new String[flv_sources_len];
				for (int i = 0; i < flv_sources_len; i++) {
					// 为了让0, 1, 2, 3 数字越小清晰度越高
					JSONObject obj = flv_sources.getJSONObject(i);
					int level = obj.getInt("level");
					qn[i] = "" + i;
					qnDesc[flv_sources_len - level] = obj.getString("name");
				}
				roomInfo.setAcceptQuality(qn);
				roomInfo.setAcceptQualityDesc(qnDesc);
			}
			roomInfo.print();
		} catch (Exception e) {
			e.printStackTrace();
			System.err.println("抖音需要cookie, 请确认cookie是否存在或失效");
			return null;
		}
		return roomInfo;
	}

	@Override
	public String getLiveUrl(String roomId, String qn, Object... obj) {
		try {
			String webcastId = (String) obj[0];
			JSONObject stream_url = null;
			if(webcastId != null) {
				Logger.println("请求仅能在移动端播放的链接");
				JSONObject room = getLiveDataByWebcastId(webcastId).getJSONObject("data").getJSONObject("room");
				stream_url = room.optJSONObject("stream_url");
			}else {
				Logger.println("请求PC Web端播放的链接");
				String jsonData = util.getContent("https://live.douyin.com/webcast/room/web/enter/?aid=6383&live_id=1&device_platform=web&language=zh-CN&enter_from=web_live&cookie_enabled=true&screen_width=1920&screen_height=1080&browser_language=zh-CN&browser_platform=Win32&browser_name=Chrome&browser_version=109.0.0.0&web_rid=" + roomId + "&enter_source=&Room-Enter-User-Login-Ab=1&is_need_double_stream=false", getPCHeader(), HttpCookies.convertCookies(cookie));
				if (jsonData == null || jsonData.trim().isEmpty() || jsonData.contains("系统繁忙，请稍后再试")) {
					Logger.println("cookie失效: " + jsonData);
					throw new IllegalArgumentException("cookie失效");
				}
				String json_str = URLDecoder.decode(jsonData, "UTF-8");
				Logger.println(json_str);
				JSONObject json = new JSONObject(json_str);
				//有时结构会发生变化
				boolean existsData = json.has("data") &&
						json.getJSONObject("data").has("data");
				if (!existsData) {
					Logger.println("json格式异常: " + json);
					throw new IllegalArgumentException("json格式异常");
				}
				JSONObject info = json.getJSONObject("data")
						.getJSONArray("data")
						.getJSONObject(0);
				stream_url = info.optJSONObject("stream_url");
			}

			JSONArray flv_sources = stream_url.getJSONObject("live_core_sdk_data").getJSONObject("pull_data")
					.getJSONObject("options").getJSONArray("qualities");
			int flv_sources_len = flv_sources.length();
			String sdk_key = null;
			for (int i = 0; i < flv_sources_len; i++) {
				JSONObject quality = flv_sources.getJSONObject(i);
				int level = quality.getInt("level");
				// 这里要与前面一致
				if (qn.equals("" + (flv_sources_len - level))) {
					sdk_key = quality.getString("sdk_key");
					break;
				}
			}

			String pull_data = stream_url.getJSONObject("live_core_sdk_data").getJSONObject("pull_data")
					.getString("stream_data");
			JSONObject data = new JSONObject(pull_data).getJSONObject("data");
			String link = data.getJSONObject(sdk_key).getJSONObject("main").getString("flv");
			Logger.println(link);
			return link;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	JSONObject getLiveDataByWebcastId(String webcastId) {
		String apiUrl = "https://webcast.amemv.com/webcast/room/reflow/info/?verifyFp=&type_id=0&live_id=1"
				+ "&sec_user_id=&app_id=1128&msToken=&X-Bogus=&room_id=" + webcastId;
		Logger.println(apiUrl);
		String json_str = util.getContent(apiUrl, getMobileHeader(), HttpCookies.convertCookies(cookie));
		Logger.println(json_str);
		return new JSONObject(json_str);
	}
	
	/**
	 * 开始录制
	 * 
	 * @param url
	 * @param fileName
	 * @param shortId
	 * @return
	 */
	@Override
	public void startRecord(String url, String fileName, String shortId) {
		HashMap<String, String> mobile = new HashMap<>();
		mobile.put("User-Agent", "Mozilla/5.0 (Android 9.0; Mobile; rv:68.0) Gecko/68.0 Firefox/68.0");
		util.download(url, fileName + ".flv", mobile);
	}
	
	private HashMap<String, String> mobileHeader;
	private HashMap<String, String> pcHeader;
	private HashMap<String, String> getPCHeader(){
		if(pcHeader == null) {
			pcHeader = new HashMap<>();
			pcHeader.put("User-Agent",
					"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:86.0) Gecko/20100101 Firefox/86.0");
			pcHeader.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			pcHeader.put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2");
		}
		return pcHeader;
	}
	private HashMap<String, String> getMobileHeader(){
		if(mobileHeader == null) {
			mobileHeader = new HashMap<>();
			mobileHeader.put("User-Agent", "Mozilla/5.0 (Android 9.0; Mobile; rv:68.0) Gecko/68.0 Firefox/68.0");
			mobileHeader.put("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
			mobileHeader.put("Accept-Language", "zh-CN,zh;q=0.8,zh-TW;q=0.7,zh-HK;q=0.5,en-US;q=0.3,en;q=0.2");
		}
		return mobileHeader;
	}

}
