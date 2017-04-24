package eu.jacquet80.rds.input;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.io.PrintStream;
import java.util.concurrent.Semaphore;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

import eu.jacquet80.rds.core.BitStreamSynchronizer;
import eu.jacquet80.rds.core.BitStreamSynchronizer.Status;
import eu.jacquet80.rds.input.group.FrequencyChangeEvent;
import eu.jacquet80.rds.input.group.GroupReaderEvent;
import eu.jacquet80.rds.log.RealTime;
import eu.jacquet80.rds.util.MathUtil;

public class SdrGroupReader extends TunerGroupReader {
	/** The sample rate at which we receive data from the tuner. */
	private static final int sampleRate = 128000;
	
	/** The sample rate for audio output. */
	private static final int outSampleRate = 48000;
	
	/* The stream from which demodulated audio data is read, linked to tunerOut */
	private final PipedInputStream syncIn;
	/* The stream to which the native plugin writes demodulated audio data. */
	private final DataOutputStream tunerOut;
	/* The audio bit reader which handles audio stream decoding and provides an audio input stream */
	private final AudioBitReader reader;
	private final BitStreamSynchronizer synchronizer;
	private final InputStream audioStream;
	private boolean synced = false;
	private boolean newGroups;
	
	/* Read/write lock for frequency-related members, must be acquired prior to accessing them */
	private ReentrantReadWriteLock frequencyLock = new ReentrantReadWriteLock();
	/* Frequency in kHz */
	private int mFrequency;
	/* Whether the frequency has changed */
	private boolean mFrequencyChanged;
	
	/* Received signal strength in dBm. Always use getter method for this. */
	private Float mRssi = new Float(0);
	
	private static final String dir, sep;
	private boolean audioCapable = false;
	private boolean audioPlaying = false;
	private final Semaphore resumePlaying = new Semaphore(0);

	public SdrGroupReader(PrintStream console, String filename) throws UnavailableInputMethod, IOException {
		syncIn = new PipedInputStream();
		tunerOut = new DataOutputStream(new PipedOutputStream(syncIn));
		reader = new AudioBitReader(new DataInputStream(syncIn), sampleRate);
		audioStream = reader.getAudioMirrorStream();
		synchronizer = new BitStreamSynchronizer(console, reader);
		
		synchronizer.addStatusChangeListener(new BitStreamSynchronizer.StatusChangeListener() {
			@Override
			public void report(Status status) {
				synced = (status == Status.SYNCED) ? true : false;
			}
		});

		File path = new File(filename);
		String absoluteLibPath = path.getAbsolutePath();
		String aFilename = path.getName();

		try {
			System.load(absoluteLibPath);
		} catch(UnsatisfiedLinkError e) {
			throw new UnavailableInputMethod(
					aFilename + ": cannot load library");
		}

		if(open()) {
			System.out.println(
					aFilename + ": device found, using it!");
			setFrequency(87500);
		} else {
			throw new UnavailableInputMethod(
					aFilename + ": no device found");
		}
		
		SoundPlayer p = new SoundPlayer();
		if(audioCapable) {
			p.start();
		}
	}

	@Override
	public boolean isStereo() {
		return false; // TODO implement stereo
		//return data.stereo;
	}

	@Override
	public boolean isSynchronized() {
		return synced;
	}

	/**
	 * @brief Switches to a new frequency.
	 * 
	 * Note that this function will request a frequency change and return immediately. The actual
	 * frequency change typically happens after the function returns. After changing the frequency,
	 * the native tuner driver will call {@link #onFrequencyChanged(int)}. To determine if the
	 * frequency has been changed, call {@link #getFrequency()}.
	 * 
	 * @param frequency The new frequency, in kHz.
	 * @return If the request was accepted, the return value is the new frequency, in kHz. If the
	 * frequency cannot be changed at this time for whatever reason, the return value is zero. Note
	 * that even an accepted request does not guarantee that the frequency will be changed.
	 */
	@Override
	public native int setFrequency(int frequency);

	/**
	 * @brief Returns the current tuner frequency in kHz.
	 */
	@Override
	public int getFrequency() {
		int ret;
		frequencyLock.readLock().lock();
		try {
			ret = mFrequency;
		} finally {
			frequencyLock.readLock().unlock();
		}
		return ret;
	}

	@Override
	public int mute() {
		audioPlaying = false;
		return 0;
	}

	@Override
	public int unmute() {
		audioPlaying = true;
		resumePlaying.release();
		return 0;
	}

	@Override
	public boolean isAudioCapable() {
		return audioCapable;
	}

	@Override
	public boolean isPlayingAudio() {
		return audioPlaying;
	}
	
	/**
	 * @brief Returns the signal strength of the current station.
	 * 
	 * Signal strength is expressed as a value between 0 and 65535, which corresponds to a range
	 * from -30 dBm to +45 dBm. This mapping was modeled after the Si470x driver, which obtains
	 * RSSI by reading from the chip's 0x0a register, which provides a 8-bit value (0-255). This is
	 * then multiplied by 873 (which would allow for a 0-75 input range without causing an
	 * overflow). Silicon Labs documentation (AN230) anecdotally indicates a practical range of 0
	 * to +45 dB, whereas measurements with a RTL2832U found values in the range of -30 to +15 dB.
	 * (The threshold for RDS reception with a RTL2832U was found around -10 dB, slightly less than
	 * half the range.)
	 * 
	 * By adding 30 dB to the RSSI as reported by the RTL2832U and applying the same multiplier as
	 * the Si470x driver, signal strengths are similar to what the Si470x driver reports under
	 * similar conditions (albeit slightly stronger, which is congruent with the fact that a
	 * RTL2832U SDR tends to yield better reception).
	 * 
	 * If the actual signal strength is outside the boundaries, the nearest boundary is returned.
	 * 
	 * The return value of this function can be calculated from signal strength as follows:
	 * signal = (rssi + 30) * 873
	 * 
	 * Vice versa:
	 * rssi = signal / 873 - 30;
	 * 
	 * @return Signal strength
	 */
	@Override
	public int getSignalStrength() {
		int signal = (int)((getRssi() + 30) * 873);
		if (signal < 0)
			signal = 0;
		else if (signal > 0xFFFF)
			signal = 0xFFFF;
		
		return signal;
	}

	@Override
	public void tune(boolean up) {
		int freq = getFrequency() + (up ? 100 : -100);
		
		if(freq > 108000) freq = 87500;
		if(freq < 87500) freq = 108000;
		
		setFrequency(freq);
	}

	@Override
	public native boolean seek(boolean up);

	@Override
	public native String getDeviceName();

	@Override
	public boolean newGroups() {
		boolean ng = newGroups;
		newGroups = false;
		return ng;
	}

	@Override
	public GroupReaderEvent getGroup() throws IOException, EndOfStream {
		GroupReaderEvent ret = null;
		
		readTuner(); // this is here for legacy reasons, the method currently does nothing
		
		if (isFrequencyChanged()) {
			// if frequency has just been changed, must report an event
			return new FrequencyChangeEvent(new RealTime(), getFrequency());
		}
		
		ret = synchronizer.getGroup();
		
		if (ret != null) {
			newGroups = true;
		}
		return ret;
	}
	
	/**
	 * @brief Returns current signal strength in dBm.
	 * 
	 * This method synchronizes all member access and can thus be called from any thread.
	 */
	private float getRssi() {
		float ret;
		synchronized(mRssi) {
			ret = mRssi;
		}
		return ret;
	}
	
	/**
	 * @brief Whether the frequency has changed since the last call to this method.
	 * 
	 * Note that this method resets the internal flag every time it is called. That is, even if the
	 * result is {@true}, subsequent calls to this method will return {@code false} unless the
	 * frequency has been changed again since the last call.
	 * 
	 * Also, if this method returns {@code true}, the frequency may have changed more than once
	 * since the last call.
	 * 
	 * This method synchronizes all member access and can thus be called from any thread.
	 */
	private boolean isFrequencyChanged() {
		boolean res = false;
		frequencyLock.writeLock().lock();
		try {
			res = mFrequencyChanged;
			mFrequencyChanged = false;
		} finally {
			frequencyLock.writeLock().unlock();
		}
		return res;
	}
	
	/**
	 * @brief Called when the tuner frequency has been changed successfully
	 * 
	 * This method notifies the {@code SdrGroupReader} about a successful frequency change.
	 * Native tuner drivers must call it immediately after changing the tuner frequency.
	 * This method synchronizes all member access and can thus be called from any thread. 
	 * 
	 * @param frequency The new frequency, in kHz
	 */
	private void onFrequencyChanged(int frequency) {
		frequencyLock.writeLock().lock();
		try {
			this.mFrequency = frequency;
			this.mFrequencyChanged = true;
		} finally {
			frequencyLock.writeLock().unlock();
		}
	}
	
	/**
	 * @brief Called when the signal strength has changed.
	 * 
	 * This method notifies the {@code SdrGroupReader} about a change in signal strength. Native
	 * tuner drivers must call it upon detecting a change. While multiple calls for the same RSSI
	 * are permissible, drivers should for performance reasons avoid calling this method unless the
	 * RSSI has actually changed. This method synchronizes all member access and can thus be called
	 * from any thread.
	 * 
	 * @param rssi
	 */
	private void onRssiChanged(float rssi) {
		synchronized(mRssi) {
			mRssi = rssi;
		}
	}
	
	/**
	 * @brief Reads data from the tuner and stores it internally.
	 * 
	 * For {@code SdrGroupReader}, this method does nothing.
	 * 
	 * Other implementations use it to retrieve RDS data, stereo flags, and RSSI from the tuner,
	 * requiring it to be called before the respective methods. This makes sense for a tuner but
	 * not for a SDR: RSSI is retrieved through an event handler method, stereo is currently not
	 * supported (TODO) and RDS data is decoded from the audio stream returned by the SDR.
	 *  
	 * @return 0
	 */
	private int readTuner() {
		return 0;
	}
	
	private native boolean open();

	static {
		dir = System.getProperty("user.dir");
		sep = System.getProperty("file.separator");
	}


	private class SoundPlayer extends Thread {
		private SourceDataLine outLine;
		private int inRatio;
		private int outRatio;

		public SoundPlayer() {
			try {
				AudioFormat inFormat =  
						new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, sampleRate, 16, 1, 2, sampleRate, false);
				AudioFormat outFormat =  
						new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, outSampleRate, 16, 1, 2, outSampleRate, false);
				DataLine.Info outInfo = new DataLine.Info(SourceDataLine.class, outFormat, 4*250000);
				outLine = (SourceDataLine) AudioSystem.getLine(outInfo);
				outLine.open(outFormat);
			} catch(Exception e) {
				System.out.println("SDR: could not open output line:");
				System.out.println("\t" + e);
				return;
			}
			
			if (sampleRate < outSampleRate) {
				System.out.println(String.format("SDR: sample rate must be %d or higher", outSampleRate));
				return;
			}
			
			int gcd = MathUtil.gcd(sampleRate, outSampleRate);
			inRatio = sampleRate / gcd;
			outRatio = outSampleRate / gcd;
			
			System.out.println(String.format("SDR audio output configured successfully, downsampling ratio %d:%d", inRatio, outRatio));
		
			audioCapable = true;
			audioPlaying = true;
		}
		
		@Override
		public void run() {
			byte[] inData = new byte[sampleRate / 2];
			byte[] outData = new byte[outSampleRate / 2];
			int inCount = 0;
			int outCount = 0;
			
			outLine.start();
			reader.startPlaying();
			
			// simple audio pass through
			while(true) {
				try {
					int len = audioStream.read(inData, 0, inData.length);
					if (sampleRate == outSampleRate)
						/* no resampling needed */
						outLine.write(inData, 0, len);
					else {
						/* resample */
						int o = 0;
						for (int i = 0; i < len; i+=2) {
							inCount += 2;
							/* 
							 * if the downsampling ratio has not been exceeded yet
							 * (outCount * inRatio <= outRatio * inCount
							 * is just an integer-friendly and div-by-zero-proof representation of 
							 * outCount/inCount <= outRatio/inRatio)
							 */
							if (outCount * inRatio <= outRatio * inCount) {
								outCount += 2;
								outData[o] = inData[i];
								outData[o+1] = inData[i+1];
								o+=2;
							}
						}
						if (o > 0)
							outLine.write(outData, 0, o);
						inCount %= inRatio;
						outCount %= outRatio;
					}
				} catch (IOException e) {
					e.printStackTrace();
				}
				
				if(! audioPlaying) {
					reader.stopPlaying();
					outLine.stop();
					outLine.flush();
					resumePlaying.acquireUninterruptibly();
					outLine.start();
					reader.startPlaying();
				}
			}
		}
	}
}
