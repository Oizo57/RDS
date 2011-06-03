package eu.jacquet80.rds.core;

import java.io.IOException;
import java.io.PrintStream;
import java.util.concurrent.Semaphore;

import eu.jacquet80.rds.input.GroupReader;
import eu.jacquet80.rds.input.StationChangeDetector;
import eu.jacquet80.rds.log.DefaultLogMessageVisitor;
import eu.jacquet80.rds.log.EndOfStream;
import eu.jacquet80.rds.log.GroupReceived;
import eu.jacquet80.rds.log.Log;

public class DecoderShell {
	private final Log log = new Log();
	
	private final Thread worker;
	
	// concurrent accesses to reader must be synchronized on DecoderShell's monitor
	private GroupReader reader;
	
	private final Semaphore groupReady = new Semaphore(0);
	
	public final static DecoderShell instance = new DecoderShell();
	
	private DecoderShell() {
		final GroupLevelDecoder groupDecoder = new GroupLevelDecoder(log);
		
		worker = new Thread() {
			public void run() {
				setName("RDS-Worker");

				try {
					boolean goOn;
					while(true) {
						groupReady.acquireUninterruptibly();
						
						synchronized(DecoderShell.this) {
							goOn = groupDecoder.processOneGroup(reader);
						}
						if(goOn) groupReady.release();
					}
				} catch (IOException e) {
					System.err.println("In RDS worker thread: " + e);
				}
			};
		};

		this.worker.start();
	}
	
	public void setConsole(final PrintStream console) {
		if(console != null) {
			this.log.addNewMessageListener(new DefaultLogMessageVisitor() {
				@Override
				public void visit(EndOfStream endOfStream) {
					console.println("\nProcessing complete.");
				}
				
				@Override
				public void visit(GroupReceived groupReceived) {
					console.printf("%04d: ", groupReceived.getBitTime() / 26);
					console.println(groupReceived);
				}
			});
		}
	}
	
	public Log getLog() {
		return this.log;
	}
	
	public synchronized void process(final GroupReader reader) {
		// implicitly, this is the end of the previous stream
		// (important to have this for UI parts that may react to stream changes)
		log.addMessage(new EndOfStream(-1));
		
		// add a station change detector
		this.reader = new StationChangeDetector(reader);
		
		this.groupReady.release();
	}
}