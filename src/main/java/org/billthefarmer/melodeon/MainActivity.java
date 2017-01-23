////////////////////////////////////////////////////////////////////////////////
//
//  Melodeon - An Android Melodeon written in Java.
//
//  Copyright (C) 2013	Bill Farmer
//
//  This program is free software; you can redistribute it and/or modify
//  it under the terms of the GNU General Public License as published by
//  the Free Software Foundation; either version 3 of the License, or
//  (at your option) any later version.
//
//  This program is distributed in the hope that it will be useful,
//  but WITHOUT ANY WARRANTY; without even the implied warranty of
//  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
//  GNU General Public License for more details.
//
//  You should have received a copy of the GNU General Public License
//  along with this program.  If not, see <http://www.gnu.org/licenses/>.
//
//  Bill Farmer	 william j farmer [at] yahoo [dot] co [dot] uk.
//
///////////////////////////////////////////////////////////////////////////////

package org.billthefarmer.melodeon;

import android.os.Bundle;
import android.preference.PreferenceManager;
import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity
    implements View.OnTouchListener, CompoundButton.OnCheckedChangeListener,
	       MidiDriver.OnMidiStartListener
{
    // Button ids

    private static final int buttons[][] =
    {{R.id.button_1, R.id.button_2,
      R.id.button_3, R.id.button_4,
      R.id.button_5, R.id.button_6,
      R.id.button_7, R.id.button_8,
      R.id.button_9, R.id.button_10},
     {R.id.button_11, R.id.button_12,
      R.id.button_13, R.id.button_14,
      R.id.button_15, R.id.button_16,
      R.id.button_17}};

    // Bass button ids

    private static final int basses[] =
    {R.id.bass_1, R.id.bass_2};

    // List of key offset values

    private static final int keyvals[] =
    {3, -2, 5, 0, -5, 2, -3};

    //	    Eb	Bb   F	 C   G	 D   A
    //	   { 3, -2,  5,	 0, -5,	 2, -3};

    // Midi notes for C Diatonic

    private static final byte notes[][] =
    {{52, 57}, // C Diatonic
     {55, 59},
     {60, 62},
     {64, 65},
     {67, 69},
     {72, 71},
     {76, 74},
     {79, 77},
     {84, 81},
     {88, 83},
     {91, 86}};

    // Chords

    private static final byte bass[][][] =
    {{{39, 51}, {46, 58}},  // Eb/Bb
     {{46, 58}, {41, 53}},  // Bb/F
     {{41, 53}, {36, 48}},  // F/C
     {{36, 48}, {43, 55}},  // C/G
     {{43, 55}, {38, 50}},  // G/D
     {{38, 50}, {45, 57}},  // D/A
     {{45, 57}, {40, 52}}}; // A/E

    private static final byte chords[][][] =
    {{{63, 70}, {70, 65}},  // Eb/Bb
     {{70, 65}, {65, 60}},  // Bb/F
     {{65, 60}, {60, 67}},  // F/C
     {{60, 67}, {67, 62}},  // C/G
     {{67, 62}, {62, 69}},  // G/D
     {{62, 69}, {69, 64}},  // D/A
     {{69, 64}, {64, 71}}}; // A/E

    // Midi codes

    private static final int noteOff = 0x80;
    private static final int noteOn  = 0x90;
    private static final int change  = 0xc0;

    // Preferences

    private final static String PREF_INSTRUMENT = "pref_instrument";
    private final static String PREF_REVERSE = "pref_reverse";
    private final static String PREF_LAYOUT = "pref_layout";
    private final static String PREF_FASCIA = "pref_fascia";
    private final static String PREF_KEY = "pref_key";

    // Layouts

    private static final int LAYOUT_MELODEON = 0;
    private static final int LAYOUT_ORGANETTO = 1;

    // Fascias

    private final static int fascias[] =
    {R.drawable.bg_onyx, R.drawable.bg_teak,
     R.drawable.bg_cherry, R.drawable.bg_rosewood,
     R.drawable.bg_olivewood};

    // Volume

    private static final int VOLUME = 96;

    // Button states

    private boolean buttonStates[][] =
    {{false, false, false, false, false, false,
      false, false, false, false},
     {false, false, false, false, false,
      false, false}};

    private boolean bassStates[] =
    {false, false};

    private boolean bellows = false;
    private boolean reverse = false;

    // Status

    private int instrument;
    private int volume;
    private int layout;
    private int fascia;
    private int key;

    // MidiDriver

    private MidiDriver midi;

    // Views

    private TextView keyView;
    private Switch revView;
    private Toast toast;

    // On create

    @Override
    protected void onCreate(Bundle savedInstanceState)
    {
	super.onCreate(savedInstanceState);

	// Get preferences

	getPreferences();

	// Set layout

	switch (layout)
	{
	case LAYOUT_MELODEON:
	    setContentView(R.layout.activity_main);
	    break;

	case LAYOUT_ORGANETTO:
	    setContentView(R.layout.activity_main_organetto);
	    break;
	}

	// Add custom view to action bar

	ActionBar actionBar = getActionBar();
	actionBar.setCustomView(R.layout.text_view);
	actionBar.setDisplayShowCustomEnabled(true);

	keyView = (TextView)actionBar.getCustomView();

	// Create midi

	midi = new MidiDriver();

	setListener();

	// Set volume, let the user adjust the volume with the
	// android volume buttons

	volume = VOLUME;
    }

    // On create option menu

    @Override
    public boolean onCreateOptionsMenu(Menu menu)
    {
	// Inflate the menu; this adds items to the action bar if it
	// is present.
	getMenuInflater().inflate(R.menu.main, menu);
	return true;
    }

    // On resume

    @Override
    protected void onResume()
    {
	super.onResume();

	// Get preferences

	getPreferences();

	// Start midi

	if (midi != null)
	    midi.start();
    }

    // On pause

    @Override
    protected void onPause()
    {
	super.onPause();

	// Save preferences

	savePreferences();

	// Stop midi

	if (midi != null)
	    midi.stop();
    }

    // On options item

    @Override
    public boolean onOptionsItemSelected(MenuItem item)
    {
	// Get id

	int id = item.getItemId();
	switch (id)
	{
	    // Settings

	case R.id.settings:
	    Intent intent = new Intent(this, SettingsActivity.class);
	    startActivity(intent);

	    return true;

	default:
	    return false;
	}
    }

    // On touch

    @Override
    public boolean onTouch(View v, MotionEvent event)
    {
	int action = event.getAction();
	int id = v.getId();

	switch (action)
	{
	    // Down

	case MotionEvent.ACTION_DOWN:
	    switch (id)
	    {
	    case R.id.bellows:
		return onBellowsDown(v, event);

	    default:
		return onButtonDown(v, event);
	    }

	    // Up

	case MotionEvent.ACTION_UP:
	    switch (id)
	    {
	    case R.id.bellows:
		return onBellowsUp(v, event);

	    default:
		return onButtonUp(v, event);
	    }

	default:
	    return false;
	}
    }

    // On checked changed

    @Override
    public void onCheckedChanged(CompoundButton button,
				 boolean isChecked)
    {
	int id = button.getId();

	switch (id)
	{
	    // Reverse switch

	case R.id.reverse:
	    reverse = isChecked;

	    // Show toast

	    if (reverse)
		showToast(R.string.buttons_reversed);

	    else
		showToast(R.string.buttons_normal);

	default:
	    return;
	}
    }

    @Override
    public void onMidiStart()
    {
	// Set instrument

	for (int i = 0; i <= buttons.length; i++)
	    midi.writeChange(change + i, instrument);
    }

    // Save preferences

    private void savePreferences()
    {
	SharedPreferences preferences =
	    PreferenceManager.getDefaultSharedPreferences(this);

	SharedPreferences.Editor editor = preferences.edit();

	editor.putBoolean(PREF_REVERSE, reverse);

	editor.commit();
    }

    // Get preferences

    private void getPreferences()
    {
	// Load preferences

	PreferenceManager.setDefaultValues(this, R.xml.preferences, false);

	SharedPreferences preferences =
	    PreferenceManager.getDefaultSharedPreferences(this);

	// Set preferences

	instrument =
	    Integer.parseInt(preferences.getString(PREF_INSTRUMENT, "21"));
	layout =
	    Integer.parseInt(preferences.getString(PREF_LAYOUT, "0"));
	fascia =
	    Integer.parseInt(preferences.getString(PREF_FASCIA, "0"));
	key =
	    Integer.parseInt(preferences.getString(PREF_KEY, "2"));

	// Set key text

	Resources resources = getResources();
	String keys[] = resources.getStringArray(R.array.pref_key_entries);

	String layouts[] =
	    resources.getStringArray(R.array.pref_layout_entries);

	if (keyView != null)
	    keyView.setText(keys[key] + "    " + layouts[layout]);

	// Set reverse

	reverse = preferences.getBoolean(PREF_REVERSE, false);

	// Set reverse switch

	if (revView != null)
	    revView.setChecked(reverse);

	// Set fascia

	View v = findViewById(R.id.fascia);

	if (v != null)
	    v.setBackgroundResource(fascias[fascia]);
    }

    // On bellows down

    private boolean onBellowsDown(View v, MotionEvent event)
    {
	if (!bellows)
	{
	    bellows = true;

	    // Change all notes

	    for (int i = 0; i < buttons.length; i++)
	    {
		for (int j = 0; j < buttons[i].length; j++)
		{
		    if (buttonStates[i][j])
		    {
		    	int k = 0;

		    	switch(i)
		    	{
		    	case 0:
			    k = reverse? buttons[i].length - j - 1: j;
			    break;

		    	case 1:
			    k = reverse? buttons[i].length - j + 1: j + 2;
			    bellows = !bellows;
			    break;
		    	}

			int note = notes[k][!bellows? 1: 0] +
			    keyvals[key];

			// Stop note

			midi.writeNote(noteOff + i, note, volume);

			note = notes[k][bellows? 1: 0] +
			    keyvals[key];

			// Play note

			midi.writeNote(noteOn + i, note, volume);

			switch (i)
			{
			case 1:
			    bellows = !bellows;
			}
		    }
		}
	    }

	    for (int i = 0; i < basses.length; i++)
	    {
		if (bassStates[i])
		{
		    // Play chord

		    int k = (reverse)? basses.length - i - 1: i;

		    switch (k)
		    {
		    case 0:
			{
			    int note =	bass[key][!bellows? 1: 0][0];
			    midi.writeNote(noteOff + 2, note, volume);

			    note =  bass[key][!bellows? 1: 0][1];
			    midi.writeNote(noteOff + 2, note, volume);

			    note =  bass[key][bellows? 1: 0][0];
			    midi.writeNote(noteOn + 2, note, volume);

			    note =  bass[key][bellows? 1: 0][1];
			    midi.writeNote(noteOn + 2, note, volume);
			}
			break;

		    case 1:
			{
			    int note =	chords[key][!bellows? 1: 0][0];
			    midi.writeNote(noteOff + 2, note, volume);

			    note =  chords[key][!bellows? 1: 0][1];
			    midi.writeNote(noteOff + 2, note, volume);

			    note =  chords[key][bellows? 1: 0][0];
			    midi.writeNote(noteOn + 2, note, volume);

			    note =  chords[key][bellows? 1: 0][1];
			    midi.writeNote(noteOn + 2, note, volume);
			}
			break;

		    }
		}
	    }
	}

	return false;
    }

    private boolean onBellowsUp(View v, MotionEvent event)
    {
	if (bellows)
	{
	    bellows = false;

	    // Change all notes

	    for (int i = 0; i < buttons.length; i++)
	    {
		for (int j = 0; j < buttons[i].length; j++)
		{
		    if (buttonStates[i][j])
		    {
			int k = 0;

			switch(i)
			{
			case 0:
			    k = reverse? buttons[i].length - j - 1: j;
			    break;

			case 1:
			    k = reverse? buttons[i].length - j + 1: j + 2;
			    bellows = !bellows;
			    break;
			}

			int note = notes[k][!bellows? 1: 0] +
			    keyvals[key];

			// Stop note

			midi.writeNote(noteOff + i, note, volume);

			note = notes[k][bellows? 1: 0] +
			    keyvals[key];

			// Play note

			midi.writeNote(noteOn + i, note, volume);

			switch (i)
			{
			case 1:
			    bellows = !bellows;
			}
		    }
		}
	    }

	    for (int i = 0; i < basses.length; i++)
	    {
		if (bassStates[i])
		{
		    // Play chord

		    int k = (reverse)? basses.length - i - 1: i;

		    switch (k)
		    {
		    case 0:
			int note = bass[key][!bellows? 1: 0][0];
			midi.writeNote(noteOff + 2, note, volume);

			note =  bass[key][!bellows? 1: 0][1];
			midi.writeNote(noteOff + 2, note, volume);

			note =  bass[key][bellows? 1: 0][0];
			midi.writeNote(noteOn + 2, note, volume);

			note =  bass[key][bellows? 1: 0][1];
			midi.writeNote(noteOn + 2, note, volume);
			break;

		    case 1:
			note = chords[key][!bellows? 1: 0][0];
			midi.writeNote(noteOff + 2, note, volume);

			note =  chords[key][!bellows? 1: 0][1];
			midi.writeNote(noteOff + 2, note, volume);

			note =  chords[key][bellows? 1: 0][0];
			midi.writeNote(noteOn + 2, note, volume);

			note =  chords[key][bellows? 1: 0][1];
			midi.writeNote(noteOn + 2, note, volume);
			break;
		    }
		}
	    }
	}
	return false;
    }

    private boolean onButtonDown(View v, MotionEvent event)
    {
	int id = v.getId();

	// Check melody buttons

	for (int i = 0; i < buttons.length; i++)
	{
	    for (int j = 0; j < buttons[i].length; j++)
	    {
		if (id == buttons[i][j] && !buttonStates[i][j])
		{
		    buttonStates[i][j] = true;

		    // Play note

		    int k = 0;

		    switch (i)
		    {
		    case 0:
			k = (reverse)? buttons[i].length - j - 1: j;
			break;

		    case 1:
			k = (reverse)? buttons[i].length - j + 1: j + 2;
			bellows = !bellows;
			break;
		    }

		    int note =	notes[k][bellows? 1: 0] + keyvals[key];
		    midi.writeNote(noteOn + i, note, volume);

		    switch (i)
		    {
		    case 1:
		    	bellows = !bellows;
		    }
		    return false;
		}
	    }
	}

	// Check bass buttons

	for (int i = 0; i < basses.length; i++)
	{
	    if (id == basses[i] && !bassStates[i])
	    {
		bassStates[i] = true;

		// Play chord

		int k = (reverse)? basses.length - i - 1: i;

		switch(k)
		{
		case 0:
		    int note = bass[key][bellows? 1: 0][0];
		    midi.writeNote(noteOn + 2, note, volume);

		    note = bass[key][bellows? 1: 0][1];
		    midi.writeNote(noteOn + 2, note, volume);
		    break;

		case 1:
		    note = chords[key][bellows? 1: 0][0];
		    midi.writeNote(noteOn + 2, note, volume);

		    note = chords[key][bellows? 1: 0][1];
		    midi.writeNote(noteOn + 2, note, volume);
		    break;
		}
	    }
	}

	return false;
    }

    private boolean onButtonUp(View v, MotionEvent event)
    {
	int id = v.getId();

	for (int i = 0; i < buttons.length; i++)
	{
	    for (int j = 0; j < buttons[i].length; j++)
	    {
		if (id == buttons[i][j] && buttonStates[i][j])
		{
		    buttonStates[i][j] = false;

		    // Stop note

		    int k = 0;

		    switch (i)
		    {
		    case 0:
			k = (reverse)? buttons[i].length - j - 1: j;
			break;

		    case 1:
			k = (reverse)? buttons[i].length - j + 1: j + 2;
			bellows = !bellows;
			break;
		    }

		    int note =	notes[k][bellows? 1: 0] + keyvals[key];
		    midi.writeNote(noteOff + i, note, 0);

		    switch (i)
		    {
		    case 1:
		    	bellows = !bellows;
		    }
		    return false;
		}
	    }
	}

	// Check bass buttons

	for (int i = 0; i < basses.length; i++)
	{
	    if (id == basses[i] && bassStates[i])
	    {
		bassStates[i] = false;

		// Stop chord

		int k = (reverse)? basses.length - i - 1: i;
		switch(k)
		{
		case 0:
		    int note = bass[key][bellows? 1: 0][0];
		    midi.writeNote(noteOff + 2, note, volume);

		    note = bass[key][bellows? 1: 0][1];
		    midi.writeNote(noteOff + 2, note, volume);
		    break;

		case 1:
		    note = chords[key][bellows? 1: 0][0];
		    midi.writeNote(noteOff + 2, note, volume);

		    note = chords[key][bellows? 1: 0][1];
		    midi.writeNote(noteOff + 2, note, volume);
		    break;
		}
		return false;
	    }
	}

	return false;
    }

    // Show toast.

    private void showToast(int key)
    {
	Resources resources = getResources();
	String text = resources.getString(key);

	showToast(text);
    }

    private void showToast(String text)
    {
	// Cancel the last one

	if (toast != null)
	    toast.cancel();

	// Make a new one

	toast = Toast.makeText(this, text, Toast.LENGTH_SHORT);
	toast.setGravity(Gravity.CENTER, 0, 0);
	toast.show();
    }

    // Set listener

    private void setListener()
    {
	View v;

	// Set listener for all buttons

	for (int i = 0; i < buttons.length; i++)
	{
	    for (int j = 0; j < buttons[i].length; j++)
	    {
		v = findViewById(buttons[i][j]);
		if (v != null)
		    v.setOnTouchListener(this);
	    }
	}

	// Bass buttons

	for (int i = 0; i < basses.length; i++)
	{
	    v = findViewById(basses[i]);
	    if (v != null)
		v.setOnTouchListener(this);
	}

	// Bellows

	v = findViewById(R.id.bellows);
	if (v != null)
	    v.setOnTouchListener(this);

	// Reverse switch

	revView = (Switch)findViewById(R.id.reverse);
	if (revView != null)
	    revView.setOnCheckedChangeListener(this);

	// Midi start

	if (midi != null)
	    midi.setOnMidiStartListener(this);
    }
}
