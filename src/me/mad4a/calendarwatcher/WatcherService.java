package me.mad4a.calendarwatcher;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioManager;
import android.os.IBinder;
import android.util.Log;
import me.mad4a.calendarwatcher.application.CalendarWatcher;
import me.mad4a.calendarwatcher.data.Calendar;
import me.mad4a.calendarwatcher.data.Event;
import me.mad4a.calendarwatcher.data.EventsQuerier;

import java.text.SimpleDateFormat;
import java.util.*;

import static me.mad4a.calendarwatcher.constants.Constants.*;

/**
 * User: chsc4698@gmail.com
 * Date: 12-3-16
 * all rights reserved
 */
public class WatcherService extends Service {

	private List<Event> events = new ArrayList<Event>();
	private AlarmManager alarmManager;
	private Set<Tuple> pushedPendingIntents;

	class Tuple {
		PendingIntent pendingIntent;
		String title;

		@Override
		public boolean equals(Object o) {
			if (this == o) {
				return true;
			}
			if (o == null || getClass() != o.getClass()) {
				return false;
			}

			Tuple tuple = (Tuple)o;

			if (pendingIntent != null ? !pendingIntent.equals(tuple.pendingIntent) : tuple.pendingIntent != null) {
				return false;
			}
			if (title != null ? !title.equals(tuple.title) : tuple.title != null) {
				return false;
			}

			return true;
		}

		@Override
		public int hashCode() {
			int result = pendingIntent != null ? pendingIntent.hashCode() : 0;
			result = 31 * result + (title != null ? title.hashCode() : 0);
			return result;
		}

		Tuple(PendingIntent pendingIntent, String title) {
			this.pendingIntent = pendingIntent;
			this.title = title;
		}
	}

	@Override
	public IBinder onBind(Intent intent) {
		return null;
	}

	@Override
	public void onCreate() {
		super.onCreate();

		pushedPendingIntents = new HashSet<Tuple>();

		alarmManager = (AlarmManager)getSystemService(ALARM_SERVICE);

		fetchEventsOfToday();

		setAlarms();
	}

	@Override
	public int onStartCommand(Intent intent, int flag, int startId) {
		super.onStartCommand(intent, flag, startId);

		Log.v(APPLICATION_TAG, "Service started");

		String action = intent.getAction();
		if (action != null) {
			if (action.equals(ACTION_DAILY_FETCH)) {
				fetchEventsOfToday();
			}
		}

		return START_STICKY;
	}

	private int getCurrentRingerMode() {
		return ((AudioManager)getSystemService(AUDIO_SERVICE)).getRingerMode();
	}

	private void setAlarm(long time, String action, String title) {
		Intent intent = new Intent(this, AlarmReceiver.class).setAction(action).putExtra(KEY_EVENT_TITLE, title);
		if (action.equals(ACTION_RESTORE_RINGER_MODE)) {
			intent.putExtra(KEY_ORIGINAL_RINGER_MODE, getCurrentRingerMode());
		} else if (action.equals(ACTION_SET_RINGER_MODE)) {
			Date now = new Date();
			if (time < now.getTime()) {
				Log.v(APPLICATION_TAG, "Trigger time has changed from " + new Date(time) + " to " + new Date());

				time = now.getTime();
			}

			intent.putExtra(KEY_RINGER_MODE, ((CalendarWatcher)getApplication()).globalRingerMode);
		}

		PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, intent, 0);
		pushedPendingIntents.add(new Tuple(pendingIntent, title));

		alarmManager.set(AlarmManager.RTC_WAKEUP, time, pendingIntent);
	}

	private boolean hourMinuteSecondLessThan(Date date1, Date date2) {
		SimpleDateFormat formatter = new SimpleDateFormat("HH:mm:ss");

		return formatter.format(date1).compareTo(formatter.format(date2)) < 0;
	}

	private void setAlarms() {
		for (Event event: events) {
			if (hourMinuteSecondLessThan(event.getEndingTimeDate(), event.getStartingTimeDate())) {
				Log.v(APPLICATION_TAG, event.getTitle() + ": invalid event, ending time less than starting time");

				continue;
			}
			if (hourMinuteSecondLessThan(event.getEndingTimeDate(), new Date())) {
				Log.v(APPLICATION_TAG, event.getTitle() + ": event passed, ignoring");

				continue;
			}

			setAlarm(event.getStartingTimeDate().getTime(), ACTION_SET_RINGER_MODE, event.getTitle());
			setAlarm(event.getEndingTimeDate().getTime(), ACTION_RESTORE_RINGER_MODE, event.getTitle());

			Log.v(APPLICATION_TAG, event.getTitle() + ": " + event.getStartingTimeDate().toString() + " starting alarm set");
			Log.v(APPLICATION_TAG, event.getTitle() + ": " + event.getEndingTimeDate().toString() + " ending alarm set");
		}
	}

	private void fetchEventsOfToday() {
		for (Calendar calendar: ((CalendarWatcher)getApplication()).checkedCalendars) {
			Log.v(APPLICATION_TAG, calendar.getTitle() + ": calendar processed");

			EventsQuerier querier = new EventsQuerier(getContentResolver(), calendar.getId());
			List<Event> eventsOfThisCalendar = querier.fetchEventsOfToday();

			for (Event event: eventsOfThisCalendar) {
				if (!event.isAllDay()) {
					events.add(event);
				}
			}
		}
		Log.v(APPLICATION_TAG, "Today's events fetched");

		((CalendarWatcher)getApplication()).setDateChangeAlarm();
	}

	@Override
	public void onDestroy() {
		((CalendarWatcher)getApplication()).globalRingerMode = null;

		for (Tuple tuple: pushedPendingIntents) {
			alarmManager.cancel(tuple.pendingIntent);

			Log.v(APPLICATION_TAG, tuple.title + " canceled");
		}

		PendingIntent dateChangeAlarm = ((CalendarWatcher)getApplication()).dateChangeAlarm;
		if (dateChangeAlarm != null) {
			alarmManager.cancel(dateChangeAlarm);

			Log.v(APPLICATION_TAG, "Date change alarm canceled");
		}

		Log.v(APPLICATION_TAG, "Service stopped");
		super.onDestroy();
	}

}
