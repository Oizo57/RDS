/*
 RDS Surveyor -- RDS decoder, analyzer and monitor tool and library.
 For more information see
   http://www.jacquet80.eu/
   http://rds-surveyor.sourceforge.net/
 
 Copyright (c) 2009 Christophe Jacquet

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
 NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT
 HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING
 FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR
 OTHER DEALINGS IN THE SOFTWARE.
*/

package eu.jacquet80.rds.log;

import java.util.LinkedList;

public class Log {
	private LinkedList<LogMessage> messages = new LinkedList<LogMessage>();
	private LinkedList<Runnable> groupListeners = new LinkedList<Runnable>();
	
	public void addMessage(LogMessage message) {
		messages.add(message);
	}
	
	public int getLastTime() {
		return messages.getLast().getBitTime();
	}
	
	public Iterable<LogMessage> messages() {
		return messages;
	}
	
	public void addGroupListener(Runnable r) {
		groupListeners.add(r);
	}
	
	public String toString() {
		StringBuffer res = null;
		for(LogMessage m : messages) {
			if(res == null) res = new StringBuffer("Log\t");
			else res.append("\n\t");
			res.append(m);
		}
		return (res == null) ? "Empty Log" : res.toString();
	}
	
	public boolean empty() {
		return messages.size() == 0;
	}
	
	public void notifyGroup() {
		for(Runnable r : groupListeners) r.run();
	}
}
