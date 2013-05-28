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

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

// MidiDriver

public class MidiDriver implements Runnable
{
    private static final int SAMPLE_RATE = 22050;
    private static final int BUFFER_SIZE = 4096;

    private Thread thread;
    private AudioTrack audioTrack;

    private OnMidiStartListener listener;

    private short shortArray[];

    private byte changeMsg[];
    private byte noteMsg[];

    // Constructor

    public MidiDriver()
    {
	// Create midi message buffers, we need two because the
	// Sonivox synthesizer won't accept three byte program
	// change messages

	changeMsg = new byte[2];
	noteMsg = new byte[3];
    }

    // Start midi

    public void start()
    {
	// Start the thread

	thread = new Thread(this, "MidiDriver");
	thread.start();
    }

    // Run

    @Override
    public void run()
    {
	processMidi();
    }

    // Stop midi

    public void stop()
    {
	Thread t = thread;
	thread = null;

	// Wait for the thread to exit

	while (t != null && t.isAlive())
	    Thread.yield();
    }

    // Process MidiDriver

    private void processMidi()
    {
	int status = 0;
	int size = 0;

	// Init midi

	if ((size = init()) == 0)
	    return;

	shortArray = new short[size];

	// Create audio track

	audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, SAMPLE_RATE,
				    AudioFormat.CHANNEL_OUT_STEREO,
				    AudioFormat.ENCODING_PCM_16BIT,
				    BUFFER_SIZE, AudioTrack.MODE_STREAM);
	if (audioTrack == null)
	{
	    shutdown();
	    return;
	}

	// Call listener

	if (listener != null)
	    listener.onMidiStart();

	// Play track

	audioTrack.play();

	// Keep running until stopped

	while (thread != null)
	{
	    // Render the audio

	    if (render(shortArray) == 0)
		break;

	    // Write audio to audiotrack

	    status = audioTrack.write(shortArray, 0, shortArray.length);

	    if (status < 0)
		break;
	}

	// Render and write the last bit of audio

	if (status > 0)
	    if (render(shortArray) > 0)
		audioTrack.write(shortArray, 0, shortArray.length);

	// Shut down audio

	shutdown();
	audioTrack.release();
    }

    // Write program change message, two bytes

    public boolean writeChange(int m, int i)
    {
	changeMsg[0] = (byte)m;
	changeMsg[1] = (byte)i;

	return write(changeMsg);
    }

    // Write note message, three bytes

    public boolean writeNote(int m, int n, int v)
    {
	noteMsg[0] = (byte)m;
	noteMsg[1] = (byte)n;
	noteMsg[2] = (byte)v;

	return write(noteMsg);
    }

    // Set listener

    public void setOnMidiStartListener(OnMidiStartListener l)
    {
	listener = l;
    }

    // Listener interface

    public interface OnMidiStartListener
    {
	public abstract void onMidiStart();
    }

    // Native midi methods

    private native int     init();
    private native int     render(short a[]);
    private native boolean write(byte a[]);
    private native boolean shutdown();

    // Load midi library

    static
    {
	System.loadLibrary("midi");
    }
}
