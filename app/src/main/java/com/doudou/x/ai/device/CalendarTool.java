package com.doudou.x.ai.device;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.doudou.x.DoudouApplication;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 日历原生工具：只保留新增日程 {@code add_calendar_event}。
 *
 * <p>基于系统 {@link CalendarContract}，不依赖任何三方库。
 * 只有在设置页打开「允许修改本机数据」，并且已授予日历读写权限时，
 * 这个工具才会进入请求体的 tools[]；其余日历工具已全部移除。
 *
 * <p>时间参数支持毫秒时间戳，或 "yyyy-MM-dd HH:mm" / "yyyy-MM-dd"，按设备当前时区解释。
 */
public final class CalendarTool implements NativeToolSet {

    private static final String LOG_TAG = "DoudouX-Native";

    public static final String ADD_EVENT = "add_calendar_event";

    private static final String[] CALENDAR_PROJECTION = {
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
    };

    @Override
    public JSONArray definitions(boolean includeWrite) {
        JSONArray array = new JSONArray();
        if (!includeWrite || !isPermissionGranted()) {
            return array;
        }
        try {
            JSONObject addProps = new JSONObject();
            putStringProp(addProps, "title", "日程标题，必填");
            putStringProp(addProps, "start", "开始时间，必填，格式 yyyy-MM-dd HH:mm 或毫秒时间戳");
            putStringProp(addProps, "end", "结束时间，必填，格式同上");
            putStringProp(addProps, "calendar_id", "可选，目标日历 ID；不填则自动选第一个可写日历");
            putStringProp(addProps, "location", "可选，地点");
            putStringProp(addProps, "description", "可选，备注");

            JSONObject addParams = new JSONObject();
            addParams.put("properties", addProps);
            addParams.put("required", stringArray("title", "start", "end"));

            array.put(function(ADD_EVENT,
                    "在本机日历新增一个日程。会改动用户数据，只应在用户明确要求时调用。",
                    addParams));
        } catch (Exception ignored) {
            // 参数固定，不会发生
        }
        return array;
    }

    @Override
    public String execute(String name, JSONObject args) {
        if (!ADD_EVENT.equals(name)) {
            return null;
        }
        return addEvent(args);
    }

    @Override
    public boolean isWriteTool(String name) {
        return ADD_EVENT.equals(name);
    }

    @Override
    public boolean isAvailable() {
        return isPermissionGranted();
    }

    @Override
    public String unavailableMessage() {
        return "没有日历读写权限，请在设置页授予「日历」权限后重试";
    }

    /** 主动检测系统权限：读写权限缺一不可，否则连工具声明都不下发。 */
    public static boolean isPermissionGranted() {
        Context context = appContext();
        return context != null
                && ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR)
                == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR)
                == PackageManager.PERMISSION_GRANTED;
    }

    // ------------------------------------------------------------------
    // 新增日程
    // ------------------------------------------------------------------

    private String addEvent(JSONObject args) {
        Context context = writableContext();
        if (context == null) {
            return contextError();
        }
        String title = args.optString("title", "").trim();
        long start = parseTime(args.optString("start", ""), -1);
        long end = parseTime(args.optString("end", ""), -1);
        if (title.isEmpty()) {
            return "缺少标题 title";
        }
        if (start < 0 || end < 0) {
            return "时间格式不正确，请用 yyyy-MM-dd HH:mm 或毫秒时间戳";
        }
        if (end <= start) {
            return "结束时间必须晚于开始时间";
        }
        long calendarId = -1;
        String requested = args.optString("calendar_id", "").trim();
        if (!requested.isEmpty()) {
            try {
                calendarId = Long.parseLong(requested);
            } catch (NumberFormatException e) {
                return "calendar_id 必须是数字";
            }
        } else {
            calendarId = firstWritableCalendarId(context);
        }
        if (calendarId < 0) {
            return "没有可用的可写日历，请检查系统日历账户，或在参数里指定 calendar_id";
        }
        ContentValues values = new ContentValues();
        values.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        values.put(CalendarContract.Events.TITLE, title);
        values.put(CalendarContract.Events.DTSTART, start);
        values.put(CalendarContract.Events.DTEND, end);
        values.put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().getID());
        putIfPresent(values, CalendarContract.Events.EVENT_LOCATION, args.optString("location", ""));
        putIfPresent(values, CalendarContract.Events.DESCRIPTION, args.optString("description", ""));
        try {
            Uri uri = context.getContentResolver().insert(CalendarContract.Events.CONTENT_URI, values);
            if (uri == null) {
                return "新增日程失败：系统未返回日程 ID";
            }
            String id = uri.getLastPathSegment();
            Log.d(LOG_TAG, "新增日程：" + title + "，id=" + id);
            return "已新增日程「" + title + "」，event_id=" + id
                    + "，时间=" + formatTime(start) + " ~ " + formatTime(end);
        } catch (Exception e) {
            Log.e(LOG_TAG, "新增日程失败：" + e.getMessage());
            return "新增日程失败：" + e.getMessage();
        }
    }

    // ------------------------------------------------------------------
    // 工具方法
    // ------------------------------------------------------------------

    /** 执行前置检查：上下文 + 系统权限。失败时返回 null，由调用方转成中文说明。 */
    private Context writableContext() {
        Context context = appContext();
        return context != null && isPermissionGranted() ? context : null;
    }

    private String contextError() {
        return appContext() == null ? "应用上下文未初始化" : unavailableMessage();
    }

    private static Context appContext() {
        DoudouApplication app = DoudouApplication.getInstance();
        return app == null ? null : app.getApplicationContext();
    }

    private static long firstWritableCalendarId(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    CalendarContract.Calendars.CONTENT_URI,
                    CALENDAR_PROJECTION,
                    CalendarContract.Calendars.VISIBLE + "=? AND "
                            + CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=?",
                    new String[]{"1", String.valueOf(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)},
                    CalendarContract.Calendars._ID + " ASC");
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getLong(0);
            }
        } catch (Exception e) {
            Log.e(LOG_TAG, "查找可写日历失败：" + e.getMessage());
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return -1;
    }

    /** 支持毫秒时间戳与两种常见日期写法，失败返回 -1。 */
    private static long parseTime(String text, long fallback) {
        if (text == null || text.trim().isEmpty()) {
            return fallback;
        }
        String value = text.trim();
        try {
            if (value.matches("\\d{10,}")) {
                return Long.parseLong(value);
            }
        } catch (NumberFormatException ignored) {
            // 落到下面的日期解析
        }
        String[] patterns = {"yyyy-MM-dd HH:mm", "yyyy-MM-dd HH:mm:ss", "yyyy-MM-dd"};
        for (String pattern : patterns) {
            try {
                SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.CHINA);
                format.setLenient(false);
                format.setTimeZone(TimeZone.getDefault());
                Date date = format.parse(value);
                return date == null ? -1 : date.getTime();
            } catch (ParseException ignored) {
                // 试下一个格式
            }
        }
        return -1;
    }

    private static String formatTime(long millis) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA);
        format.setTimeZone(TimeZone.getDefault());
        return format.format(new Date(millis));
    }

    private static void putIfPresent(ContentValues values, String key, String value) {
        if (value != null && !value.isEmpty()) {
            values.put(key, value);
        }
    }

    private static JSONArray stringArray(String... values) {
        JSONArray array = new JSONArray();
        for (String value : values) {
            array.put(value);
        }
        return array;
    }

    private static void putStringProp(JSONObject props, String name, String description) {
        try {
            JSONObject prop = new JSONObject();
            prop.put("type", "string");
            prop.put("description", description);
            props.put(name, prop);
        } catch (Exception ignored) {
            // key 非空，不会发生
        }
    }

    private static JSONObject function(String name, String description, JSONObject parameters) {
        JSONObject function = new JSONObject();
        JSONObject tool = new JSONObject();
        try {
            if (parameters.length() == 0) {
                parameters.put("type", "object");
                parameters.put("properties", new JSONObject());
            }
            function.put("name", name);
            function.put("description", description);
            function.put("parameters", parameters);
            tool.put("type", "function");
            tool.put("function", function);
        } catch (Exception ignored) {
            // key 非空，不会发生
        }
        return tool;
    }
}
