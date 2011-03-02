/*
 Part of the ReplicatorG project - http://www.replicat.org
 Copyright (c) 2008 Zach Smith

 This program is free software; you can redistribute it and/or modify
 it under the terms of the GNU General Public License as published by
 the Free Software Foundation; either version 2 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU General Public License for more details.

 You should have received a copy of the GNU General Public License
 along with this program; if not, write to the Free Software Foundation,
 Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package replicatorg.machine;

import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;
import java.util.Vector;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

import javax.swing.JOptionPane;

import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import replicatorg.app.Base;
import replicatorg.app.GCodeParser;
import replicatorg.app.exceptions.BuildFailureException;
import replicatorg.app.tools.XML;
import replicatorg.app.ui.MainWindow;
import replicatorg.drivers.Driver;
import replicatorg.drivers.DriverFactory;
import replicatorg.drivers.DriverQueryInterface;
import replicatorg.drivers.EstimationDriver;
import replicatorg.drivers.OnboardParameters;
import replicatorg.drivers.RetryException;
import replicatorg.drivers.SDCardCapture;
import replicatorg.drivers.SimulationDriver;
import replicatorg.drivers.StopException;
import replicatorg.drivers.UsesSerial;
import replicatorg.drivers.commands.DriverCommand;
import replicatorg.machine.MachineState.State;
import replicatorg.machine.model.MachineModel;
import replicatorg.machine.model.ToolModel;
import replicatorg.model.GCodeSource;
import replicatorg.model.StringListSource;

/**
 * The MachineController object controls a single machine. It contains a single
 * machine driver object. All machine operations (building, stopping, pausing)
 * are performed asynchronously by a thread maintained by the MachineController;
 * calls to MachineController ordinarily trigger an operation and return
 * immediately.
 * 
 * When the machine is paused, the machine thread waits on notification to the machine thread object.
 * 
 * In general, the machine thread should *not* be interrupted, as this can cause synchronization issues.
 * Interruption should only really happen on hanging connections and shutdown.
 * 
 * @author phooky
 * 
 */
public class MachineController implements MachineControllerInterface {

	public enum RequestType {
		CONNECT,			// Establish connection with the driver
		RESET,				// ??

		SIMULATE,			// Build to the simulator
		BUILD_DIRECT,		// Build in real time on the machine
		BUILD_TO_FILE,		// Build, but instruct the machine to save it to the local filesystem
		BUILD_TO_REMOTE_FILE,				// Build, but instruct the machine to save it to the machine's filesystem
		BUILD_REMOTE,		// Instruct the machine to run a build from it's filesystem
		
		PAUSE,				// Pause the current build
		UNPAUSE,			// Unpause the current build
		STOP,				// Abort the current build
		DISCONNECT_REMOTE_BUILD,	// Disconnect from a remote build without stopping it.
		
		RUN_COMMAND,		// Run a single command on the driver, interleaved with the build.
	}
	
	public enum JobTarget {
		/** No target selected. */
		NONE,
		/** Operations are performed on a physical machine. */
		MACHINE,
		/** Operations are being simulated. */
		SIMULATOR,
		/** Operations are being captured to an SD card on the machine. */
		REMOTE_FILE,
		/** Operations are being captured to a file. */
		FILE
	};
	
	
	// Test idea for a request interface between the thread and the controller
	class JobRequest {

		RequestType type;
		GCodeSource source;
		String remoteName;
		DriverCommand command;
		
		public JobRequest(RequestType type,
							GCodeSource source,
							String remoteName) {
			this.type = type;
			this.source = source;
			this.remoteName = remoteName;
		}

		public JobRequest(RequestType type,
							DriverCommand command) {
			this.type = type;
			this.command = command;
		}
	}
	
	// Test idea for a print job: specifies a gcode source and a target
	class JobInformation {
		JobTarget target;
		GCodeSource source;
		
		public JobInformation(JobTarget target, GCodeSource source) {
			
		}
	}
	
	private MachineState state = new MachineState();
	
	/**
	 * Get the machine state.  This is a snapshot of the state when the method was called, not a live object.
	 * @return a copy of the machine's state object
	 */
	public MachineState getMachineState() { return state.clone(); }
	
	/**
	 * Set the a machine state.  If the state is not the current state, a state change
	 * event will be emitted and the machine thread will be notified.  
	 * @param state the new state of the machine.
	 */
	private void setState(MachineState state) {
		MachineState oldState = this.state;
		this.state = state;
		if (!oldState.equals(state)) {
			emitStateChange(oldState,state);
			// wake up machine thread
			synchronized(machineThread) {
				machineThread.notify(); // wake up paused machines
			}
		}
	}

	/**
	 * A helper for setting the machine state to a simple state.
	 * @param state The new state.
	 */
	private void setState(MachineState.State state) {
		MachineState newState = getMachineState();
		newState.setState(state);
		setState(newState);
	}

	// Build statistics
	private int linesProcessed = -1;
	private int linesTotal = -1;
	private double startTimeMillis = -1;
	
	
	/**
	 * The MachineThread is responsible for communicating with the machine.
	 * 
	 * @author phooky
	 * 
	 */
	class MachineThread extends Thread {
		private long lastPolled = 0;
		private boolean pollingEnabled = false;
		private long pollIntervalMs = 1000;

		GCodeSource currentSource;
		JobTarget currentTarget;
		
		private boolean running = true;
		
		String remoteName = null;
		
		// Link of job requests to run
		ConcurrentLinkedQueue<JobRequest> pendingQueue;
		
		public MachineThread() {
			pendingQueue = new ConcurrentLinkedQueue<JobRequest>();
		}

		/**
		 * Build the provided gcodes.  This method does not return until the build is complete or has been terminated.
		 * The build target need not be an actual machine; it can be a file as well.  An "upload" is considered a build
		 * to a machine.  
		 * @param source The gcode to build.
		 * @return true if build terminated normally
		 * @throws BuildFailureException
		 * @throws InterruptedException
		 */
		private boolean buildCodesInternal(GCodeSource source) throws BuildFailureException, InterruptedException {
			
			if (!state.isBuilding()) {
				// Do not build if the machine is not building or paused
				return false;
			}
			
			// Flush any parser cached data
			if (driver == null) {
				Base.logger.severe("Machinecontroller driver is null, can't print");
				return false;
			}
			
			// Set up a parser to talk to the driver
			GCodeParser parser = new GCodeParser();
			
			// Queue of commands that we get from the parser, and run on the driver.
			Queue<DriverCommand> driverQueue = new LinkedList< DriverCommand >();
			
			parser.init((DriverQueryInterface) driver);
			
			// And the one for the simulator
			GCodeParser simulationParser = new GCodeParser();
			
			// Queue of commands that we get from the parser, and run on the driver.
			Queue<DriverCommand> simulatorQueue = new LinkedList< DriverCommand >();
			
			simulationParser.init((DriverQueryInterface) simulator);
			
			// And make a file to write simulation commands out from
//			BufferedWriter out = null;
//			try{
//				FileWriter fstream = new FileWriter("driver_codes.txt");
//    	        out = new BufferedWriter(fstream);
//    	    }catch (Exception e){//Catch exception if any
//	    	      System.err.println("Error opening file for output: " + e.getMessage());
//			}
			
			// Initialize our gcode provider
			Iterator<String> i = source.iterator();
			
			boolean retry = false;
			// Iterate over all the lines in the gcode source.
			while (i.hasNext()) {
				// Read and process next line
				if (retry == false) {
					String line = i.next();
					linesProcessed++;
					if (Thread.currentThread().isInterrupted()) {
						throw new BuildFailureException("Build was interrupted");
					}
					
					if (simulator.isSimulating()) {
						simulationParser.parse(line, simulatorQueue);
					}
					
					if (!isSimulating()) {
						// Parse a line for the actual machine
						parser.parse(line, driverQueue);
						
//						try {
//							out.write(line + ": " + simulatorQueue.size() + " instructions\n");
//							for (DriverCommand command : simulatorQueue) {
//								out.write("  " + command.contentsToString() + "\n");
//							}
//							out.flush();
//						} catch (IOException e1) {}
					}
				}
				
				
				// Simulate the command. Just run everything against the simulator, and ignore errors.
				if (retry == false && simulator.isSimulating()) {
					for (DriverCommand command : simulatorQueue) {
						try {
							command.run(simulator);
						} catch (RetryException r) {
							// Ignore.
						} catch (StopException e) {
							// TODO: stop the simulator at this point?
						}
					}
					simulatorQueue.clear();
				}
				
				try {
					if (!isSimulating()) {
						// Run the command on the machine.
						while(!driverQueue.isEmpty()) {
							driverQueue.peek().run(driver);
							driverQueue.remove();
						}
					}
					retry = false;
				} catch (RetryException r) {
					// Indicate that we should retry the current line, rather
					// than proceeding to the next, on the next go-round.
					Base.logger.log(Level.FINE,"Message delivery failed, retrying");
					retry = true;
				} catch (StopException e) {
					// TODO: Just returning here seems dangerous, better to notify the state machine.
					
					switch (e.getType()) {
					case UNCONDITIONAL_HALT:
						JOptionPane.showMessageDialog(null, e.getMessage(), 
								"Unconditional halt: build ended", JOptionPane.INFORMATION_MESSAGE);
						return true;
					case PROGRAM_END:
						JOptionPane.showMessageDialog(null, e.getMessage(),
								"Program end: Build ended", JOptionPane.INFORMATION_MESSAGE);
						return true;
					case OPTIONAL_HALT:
						int result = JOptionPane.showConfirmDialog(null, e.getMessage(),
								"Optional halt: Continue build?", JOptionPane.YES_NO_OPTION);
						
						if (result == JOptionPane.YES_OPTION) {
							driverQueue.remove();
						} else {
							return true;
						}
						break;
					case PROGRAM_REWIND:
						// TODO: Implement rewind; for now, just stop the build.
						JOptionPane.showMessageDialog(null, e.getMessage(),
								"Program rewind: Build ended", JOptionPane.INFORMATION_MESSAGE);
						return true;
					}
				}
				
				// did we get any errors?
				if (!isSimulating()) {
					driver.checkErrors();
				}
				
				// are we paused?
				if (state.isPaused()) {
					// Tell machine to enter pause mode
					if (!isSimulating()) driver.pause();
					while (state.isPaused()) {
						// Sleep until notified
						synchronized(this) { wait(); }
					}
					// Notified; tell machine to wake up.
					if (!isSimulating()) driver.unpause();
				}
				
				// Send a stop command if we're stopping.
				if (state.getState() == MachineState.State.STOPPING ||
						state.getState() == MachineState.State.RESET) {
					if (!isSimulating()) {
						driver.stop(true);
					}
					throw new BuildFailureException("Build manually aborted");
				}

				// bail if we're no longer building
				if (state.getState() != MachineState.State.BUILDING) {
					return false;
				}
				
				// send out updates
				if (pollingEnabled) {
					long curMillis = System.currentTimeMillis();
					if (lastPolled + pollIntervalMs <= curMillis) {
						lastPolled = curMillis;
						pollStatus();
					}
				}
				MachineProgressEvent progress = 
					new MachineProgressEvent((double)System.currentTimeMillis()-startTimeMillis,
							estimatedBuildTime,
							linesProcessed,
							linesTotal);
				emitProgress(progress);
				
				while (!pendingQueue.isEmpty()) {
					runRequest(pendingQueue.remove());
				}
			}
			
			// wait for driver to finish up.
			if (!isSimulating()) {
				while (!driver.isFinished()) {
					// We're checking for stop/reset here as well. This will catch stops occurring
					// after all lines have been queued on the motherboard.
					
					// Send a stop command if we're stopping.
					if (state.getState() == MachineState.State.STOPPING ||
						state.getState() == MachineState.State.RESET) {
						if (!isSimulating()) {
							driver.stop(true);
						}
						throw new BuildFailureException("Build manually aborted");
					}
					// bail if we're no longer building
					if (state.getState() != MachineState.State.BUILDING) {
						return false;
					}
					Thread.sleep(100);
				}
			}
			return true;
		}

		// Build the gcode source, bracketing it with warmup and cooldown commands.
		// 
		private void buildInternal(GCodeSource source) {
			startTimeMillis = System.currentTimeMillis();
			linesProcessed = 0;
			linesTotal = warmupCommands.size() + 
				cooldownCommands.size() +
				source.getLineCount();
			
			startStatusPolling(1000); // Will not send commands if temp mon. turned off
			try {
				if (!isSimulating()) {
					driver.getCurrentPosition(); // reconcile position
				}
				
				Base.logger.info("Running warmup commands");
				buildCodesInternal(new StringListSource(warmupCommands));
				
				Base.logger.info("Running build.");
				buildCodesInternal(source);
				
				Base.logger.info("Running cooldown commands");
				buildCodesInternal(new StringListSource(cooldownCommands));
				
				if (!isSimulating()) {
					driver.invalidatePosition();
				}
				setState(new MachineState(driver.isInitialized()?
						MachineState.State.READY:
						MachineState.State.NOT_ATTACHED
					));
			} catch (BuildFailureException e) {
				if (isSimulating()) {
					// If simulating, return to connected or
					// disconnected state.
					setState(new MachineState(driver.isInitialized()?
							MachineState.State.READY:
							MachineState.State.NOT_ATTACHED));
				} else {
					// If a real interrupted build,
					// Attempt to reestablish connection to check state on an abort
					// or failure
					setState(new MachineState(MachineState.State.CONNECTING));
				}
			} catch (InterruptedException e) {
				Base.logger.warning("MachineController interrupted");
			} finally {
				stopStatusPolling();
			}
		}
		
		// Run a remote SD card build on the machine.
		private void buildRemoteInternal(String remoteName) {
			// Dump out if SD builds are unsupported on this machine
			if (remoteName == null || !(driver instanceof SDCardCapture)) return;
			if (state.getState() != MachineState.State.BUILDING_REMOTE) return;
			driver.getCurrentPosition(); // reconcile position
			SDCardCapture sdcc = (SDCardCapture)driver;
			if (!processSDResponse(sdcc.playback(remoteName))) {
				setState(MachineState.State.STOPPING);
				return;
			}
			// Poll for completion until done.  Check for pause states as well.
			while (running && !driver.isFinished()) {
				try {
					// are we paused?
					if (state.isPaused()) {
						driver.pause();
						while (state.isPaused()) {
							synchronized(this) { wait(); }
						}
						driver.unpause();
					}

					// bail if we got interrupted.
					if (state.getState() != MachineState.State.BUILDING_REMOTE) return;
					synchronized(this) { wait(1000); }// wait one second.  A pause will notify us to check the pause state.
				} catch (InterruptedException e) {
					// bail if we got interrupted.
					if (state.getState() != MachineState.State.BUILDING_REMOTE) return;
				}
			}
			driver.invalidatePosition();
			setState(new MachineState(MachineState.State.READY));
		}
		
		private boolean startBuildToRemoteFile() {
			if (!(driver instanceof SDCardCapture)) {
				return false;
			}
			
			SDCardCapture sdcc = (SDCardCapture)driver;
			if (processSDResponse(sdcc.beginCapture(remoteName))) { 
				buildInternal(currentSource);
				Base.logger.info("Captured bytes: " +Integer.toString(sdcc.endCapture()));
				return true;
			}

			return false;
		}
		
		private boolean startBuildToFile() {
			if (!(driver instanceof SDCardCapture)) {
				return false;
			}
			
			SDCardCapture sdcc = (SDCardCapture)driver;
			try {
				sdcc.beginFileCapture(remoteName); 
				buildInternal(currentSource);
				sdcc.endFileCapture();
				return true;
			} catch (Exception e) {
				e.printStackTrace();
			}
			
			return false;
		}
		
		
		private void runRequest(JobRequest request) {
			switch(request.type) {
			case CONNECT:
				if (state.getState() == MachineState.State.NOT_ATTACHED) {
					setState(new MachineState(MachineState.State.CONNECTING));
				}
				break;
			case RESET:
				if (state.isConnected()) {
					setState(new MachineState(MachineState.State.RESET));
				}
				break;
			case SIMULATE:
				currentSource = source;
				currentTarget = JobTarget.SIMULATOR;
				setState(new MachineState(MachineState.State.BUILDING));
				break;
			case BUILD_DIRECT:
				currentSource = request.source;
				currentTarget = JobTarget.MACHINE;
				setState(new MachineState(MachineState.State.BUILDING));
				break;
			case BUILD_TO_FILE:
				currentSource = request.source;
				this.remoteName = request.remoteName;
				currentTarget = JobTarget.FILE;
				setState(new MachineState(MachineState.State.BUILDING));
				break;
			case BUILD_TO_REMOTE_FILE:
				currentSource = request.source;
				this.remoteName = request.remoteName;
				currentTarget = JobTarget.REMOTE_FILE;
				setState(new MachineState(MachineState.State.BUILDING));
				break;
			case BUILD_REMOTE:
				this.remoteName = request.remoteName;
				setState(MachineState.State.BUILDING_REMOTE);
				break;
			case PAUSE:
				if (state.isBuilding() && !state.isPaused()) {
					MachineState newState = getMachineState();
					newState.setPaused(true);
					setState(newState);
				}
				break;
			case UNPAUSE:
				if (state.isBuilding() && state.isPaused()) {
					MachineState newState = getMachineState();
					newState.setPaused(false);
					setState(newState);
				}
				break;
			case STOP:
				// TODO: Do more than just turn off the heaters here?
				driver.getMachine().currentTool().setTargetTemperature(0);
				driver.getMachine().currentTool().setPlatformTargetTemperature(0);
				if (state.isBuilding()) {
					setState(MachineState.State.STOPPING);
				}
				break;
			case DISCONNECT_REMOTE_BUILD:
				if (state.getState() == MachineState.State.BUILDING_REMOTE) {
					running = false;
					return; // send no further packets to machine; let it go on its own
				}
				
				if (state.isBuilding()) {
					setState(MachineState.State.STOPPING);
				}
				running = false;
				break;
			case RUN_COMMAND:
				{
					boolean completed = false;
					// TODO: provide feedback to the caller rather than eating it.
					
					while(!completed) {
						try {
							request.command.run(driver);
							completed = true;
						} catch (RetryException e) {
						} catch (StopException e) {
						}
					}
				}
				break;
			}
		}
		
		
		/**
		 * Main machine thread loop.
		 */
		public void run() {
			// This is our main loop.
			while (running || state.getState() == MachineState.State.STOPPING) {
 
				// First, check for and run any control requests that might be in the queue.
				while (!pendingQueue.isEmpty()) {
					runRequest(pendingQueue.remove());
				}
				
				try {
					if (state.getState() == MachineState.State.BUILDING) {
						// Build to remote file
						if (currentTarget == JobTarget.REMOTE_FILE) {
							
							if (!startBuildToRemoteFile()) {
								setState(MachineState.State.STOPPING);
							}
						
						// Build to local file
						} else if (currentTarget == JobTarget.FILE) {

							if (!startBuildToFile()) {
								setState(MachineState.State.STOPPING);
							}

						// Ordinary build
						} else {
							buildInternal(currentSource);
						}
						
					} else if (state.getState() == MachineState.State.BUILDING_REMOTE) {
						buildRemoteInternal(remoteName);
						
					} else if (state.getState() == MachineState.State.CONNECTING) {
						driver.initialize();
						if (driver.isInitialized()) {
							readName();
							setState(MachineState.State.READY);
						} else {
							setState(MachineState.State.NOT_ATTACHED);
						}
						
					} else if (state.getState() == MachineState.State.STOPPING) {
						driver.stop(true);
						setState(MachineState.State.READY);
						
					} else if (state.getState() == MachineState.State.RESET) {
						driver.reset();
						readName();
						setState(MachineState.State.READY);
						
					} else {
						if (state.getState() == MachineState.State.NOT_ATTACHED) {
							// Kill serial port connection when not attached, to make it safe to unplug
							if (driver instanceof UsesSerial) {
								UsesSerial us = (UsesSerial)driver;
								us.setSerial(null);
							}
						}
						synchronized(this) {
							if (state.getState() == MachineState.State.READY ||
									state.getState() == MachineState.State.NOT_ATTACHED ||
									state.isPaused()) {
								wait();
							} else {
							}
						}
					}
				} catch (InterruptedException ie) {
					return;
				} catch (Exception e) {
					e.printStackTrace();
				}
			}
		}
		
		/**
		 * Start polling the machine for its current status (temperatures, etc.)
		 * @param interval The interval, in ms, between polls
		 */
		private void startStatusPolling(long interval) {
			pollingEnabled = true;
			pollIntervalMs = interval;
		}

		/**
		 * Turn off status polling.
		 */
		private void stopStatusPolling() {
			pollingEnabled = false;
		}

		private void pollStatus() {
			if (state.isBuilding() && !isSimulating()) {
				if (Base.preferences.getBoolean("build.monitor_temp",false)) {
					driver.readTemperature();
					emitToolStatus(driver.getMachine().currentTool());
				}
			}
		}
		
		synchronized public boolean scheduleRequest(JobRequest request) {
			pendingQueue.add(request);
				
			synchronized(machineThread) {
				machineThread.notify(); // wake up paused machines
			}
			
			return true;
		}
		
		public boolean isReady() { return state.isReady(); }
		
		// TODO: Put this somewhere else
		/** True if the machine's build is going to the simulator. */
		public boolean isSimulating() {
			return (state.getState() == MachineState.State.BUILDING
					&& currentTarget == JobTarget.SIMULATOR);
		}
		
		// TODO: Put this somewhere else
		public boolean isInteractiveTarget() {
			return currentTarget == JobTarget.MACHINE ||
				currentTarget == JobTarget.SIMULATOR; 	
		}
	}
	
	private void readName() {
		if (driver instanceof OnboardParameters) {
			String n = ((OnboardParameters)driver).getMachineName();
			if (n != null && n.length() > 0) {
				name = n;
			}
			else {
				parseName(); // Use name from XML file instead of reusing name from last connected machine
			}
		}
	}
	MachineThread machineThread = new MachineThread();
	
	// The GCode source of the current build source.
	protected GCodeSource source;
	
	// this is the xml config for this machine.
	protected Node machineNode;

	// The name of our machine.
	protected String name;

	public String getName() { return name; }
	
	// Our driver object. Null when no driver is selected.
	protected Driver driver = null;
	
	// the simulator driver
	protected SimulationDriver simulator;

	// our current thread.
	protected Thread thread;
	
	// estimated build time in millis
	protected double estimatedBuildTime = 0;

	// our warmup/cooldown commands
	protected Vector<String> warmupCommands;

	protected Vector<String> cooldownCommands;

	/**
	 * Creates the machine object.
	 */
	public MachineController(Node mNode) {
		// save our XML
		machineNode = mNode;

		parseName();
		Base.logger.info("Loading machine: " + name);

		// load our various objects
		loadDriver();
		loadExtraPrefs();
		machineThread = new MachineThread();
		machineThread.start();
	}

	public void setCodeSource(GCodeSource source) {
		this.source = source;
	}

	// TODO: hide this behind an API
//	private MainWindow window; // for responses to errors, etc.
//	public void setMainWindow(MainWindow window) { this.window = window; }
	public void setMainWindow(MainWindow window) {  }

	static Map<SDCardCapture.ResponseCode,String> sdErrorMap =
		new EnumMap<SDCardCapture.ResponseCode,String>(SDCardCapture.ResponseCode.class);
	{
		sdErrorMap.put(SDCardCapture.ResponseCode.FAIL_NO_CARD,
				"No SD card was detected.  Please make sure you have a working, formatted\n" +
				"SD card in the motherboard's SD slot and try again.");
		sdErrorMap.put(SDCardCapture.ResponseCode.FAIL_INIT,
				"ReplicatorG was unable to initialize the SD card.  Please make sure that\n" +
				"the SD card works properly.");
		sdErrorMap.put(SDCardCapture.ResponseCode.FAIL_PARTITION,
				"ReplicatorG was unable to read the SD card's partition table.  Please check\n" +
				"that the card is partitioned properly.\n" +
				"If you believe your SD card is OK, try resetting your device and restarting\n" +
				"ReplicatorG."
				);
		sdErrorMap.put(SDCardCapture.ResponseCode.FAIL_FS,
				"ReplicatorG was unable to open the filesystem on the SD card.  Please make sure\n" +
				"that the SD card has a single partition formatted with a FAT16 filesystem.");
		sdErrorMap.put(SDCardCapture.ResponseCode.FAIL_ROOT_DIR,
				"ReplicatorG was unable to read the root directory on the SD card.  Please\n"+
				"check to see if the SD card was formatted properly.");
		sdErrorMap.put(SDCardCapture.ResponseCode.FAIL_LOCKED,
				"The SD card cannot be written to because it is locked.  Remove the card,\n" +
				"switch the lock off, and try again.");
		sdErrorMap.put(SDCardCapture.ResponseCode.FAIL_NO_FILE,
				"ReplicatorG could not find the build file on the SD card.");
		sdErrorMap.put(SDCardCapture.ResponseCode.FAIL_GENERIC,"Unknown SD card error.");
	}
	
	/**
	 * Process an SD response code and throw up an appropriate dialog for the user.
	 * @param code the response from the SD request
	 * @return true if the code indicates success; false if the operation should be aborted
	 */
	public boolean processSDResponse(SDCardCapture.ResponseCode code) {
		if (code == SDCardCapture.ResponseCode.SUCCESS) return true;
		String message = sdErrorMap.get(code);
		JOptionPane.showMessageDialog(
//				window,
				null,
				message,
				"SD card error",
				JOptionPane.ERROR_MESSAGE);
		return false;
	}

	private String descriptorName;
	
	public String getDescriptorName() { return descriptorName; }
	
	private void parseName() {
		NodeList kids = machineNode.getChildNodes();

		for (int j = 0; j < kids.getLength(); j++) {
			Node kid = kids.item(j);

			if (kid.getNodeName().equals("name")) {
				descriptorName = kid.getFirstChild().getNodeValue().trim();
				name = descriptorName;
				return;
			}
		}

		name = "Unknown";
	}

	public boolean buildRemote(String remoteName) {
		machineThread.scheduleRequest(new JobRequest(RequestType.BUILD_REMOTE, null, remoteName));
		return true;
	}
	
	/**
	 * Begin running a job.
	 */
	public boolean execute() {
		// start simulator
		if (simulator != null && Base.preferences.getBoolean("build.showSimulator",false))
			simulator.createWindow();

		// estimate build time.
		Base.logger.info("Estimating build time...");
		estimate();

		// do that build!
		Base.logger.info("Beginning build.");
		
		machineThread.scheduleRequest(new JobRequest(RequestType.BUILD_DIRECT, source, null));
		return true;
	}

	public boolean simulate() {
		// start simulator
		if (simulator != null)
			simulator.createWindow();

		// estimate build time.
		Base.logger.info("Estimating build time...");
		estimate();

		// do that build!
		Base.logger.info("Beginning simulation.");
		machineThread.scheduleRequest(new JobRequest(RequestType.SIMULATE, source, null));
		return true;
	}


	public void estimate() {
		if (source == null) { return; }

		EstimationDriver estimator = new EstimationDriver();
		estimator.setMachine(loadModel());
		
		Queue<DriverCommand> estimatorQueue = new LinkedList< DriverCommand >();
		
		GCodeParser estimatorParser = new GCodeParser();
		estimatorParser.init(estimator);

		// run each line through the estimator
		for (String line : source) {
			// TODO: Hooks for plugins to add estimated time?
			estimatorParser.parse(line, estimatorQueue);
			
			for (DriverCommand command : estimatorQueue) {
				try {
					command.run(estimator);
				} catch (RetryException r) {
					// Ignore.
				} catch (StopException e) {
					// TODO: Should we stop the estimator when we get a stop???
				}
			}
			estimatorQueue.clear();
		}

		if (simulator != null) {
			simulator.setSimulationBounds(estimator.getBounds());
		}
		// oh, how this needs to be cleaned up...
		if (driver instanceof SimulationDriver) {
			((SimulationDriver)driver).setSimulationBounds(estimator.getBounds());
		}
		estimatedBuildTime = estimator.getBuildTime();
		Base.logger.info("Estimated build time is: "
				+ EstimationDriver.getBuildTimeString(estimatedBuildTime));
	}

	private MachineModel loadModel() {
		MachineModel model = new MachineModel();
		model.loadXML(machineNode);
		return model;
	}
		
	private void loadDriver() {
		// load our utility drivers
		if (Base.preferences.getBoolean("machinecontroller.simulator",true)) {
			Base.logger.info("Loading simulator.");
			simulator = new SimulationDriver();
			simulator.setMachine(loadModel());
		}
		Node driverXml = null; 
		// load our actual driver
		NodeList kids = machineNode.getChildNodes();
		for (int j = 0; j < kids.getLength(); j++) {
			Node kid = kids.item(j);
			if (kid.getNodeName().equals("driver")) {
				driverXml = kid;
			}
		}
		driver = DriverFactory.factory(driverXml);
		driver.setMachine(getModel());
		// Initialization is now handled by the machine thread when it
		// is placed in a connecting state.
	}

	private void loadExtraPrefs() {
		String[] commands = null;
		String command = null;

		warmupCommands = new Vector<String>();
		if (XML.hasChildNode(machineNode, "warmup")) {
			String warmup = XML.getChildNodeValue(machineNode, "warmup");
			commands = warmup.split("\n");

			for (int i = 0; i < commands.length; i++) {
				command = commands[i].trim();
				warmupCommands.add(new String(command));
				// System.out.println("Added warmup: " + command);
			}
		}

		cooldownCommands = new Vector<String>();
		if (XML.hasChildNode(machineNode, "cooldown")) {
			String cooldown = XML.getChildNodeValue(machineNode, "cooldown");
			commands = cooldown.split("\n");

			for (int i = 0; i < commands.length; i++) {
				command = commands[i].trim();
				cooldownCommands.add(new String(command));
				// System.out.println("Added cooldown: " + command);
			}
		}
	}

	public DriverQueryInterface getDriverQueryInterface() {
		return (DriverQueryInterface)driver;
	}
	
	public Driver getDriver() {
		Base.logger.severe("The driver should not be referenced directly!");
		return driver;
	}

	public SimulationDriver getSimulatorDriver() {
		return simulator;
	}

	MachineModel cachedModel = null;
	
	public MachineModel getModel() {
		if (cachedModel == null) { cachedModel = loadModel(); }
		return cachedModel;
	}

	public void stop() {
		machineThread.scheduleRequest(new JobRequest(RequestType.STOP, null, null));
	}

	synchronized public boolean isInitialized() {
		return (driver != null && driver.isInitialized());
	}

	public void pause() {
		machineThread.scheduleRequest(new JobRequest(RequestType.PAUSE, null, null));
	}

	public void upload(String remoteName) {
		/**
		 * Upload the gcode to the given remote SD name.
		 * @param source
		 * @param remoteName
		 */
		machineThread.scheduleRequest(new JobRequest(RequestType.BUILD_TO_REMOTE_FILE, source, remoteName));
	}

	public void buildToFile(String path) {
		// TODO: what happened to this?
	}

	
	public void unpause() {
		machineThread.scheduleRequest(new JobRequest(RequestType.UNPAUSE, null, null));
	}

	public void reset() {
		machineThread.scheduleRequest(new JobRequest(RequestType.RESET, null, null));
	}

	public void connect() {
		// recreate thread if stopped
		// TODO: Evaluate this!
		if (!machineThread.isAlive()) {
			machineThread = new MachineThread();
			machineThread.start();
		}
		machineThread.scheduleRequest(new JobRequest(RequestType.CONNECT, null, null));
	}

	synchronized public void disconnect() {
		driver.uninitialize();
		setState(new MachineState(MachineState.State.NOT_ATTACHED));
	}

	synchronized public boolean isPaused() {
		return getMachineState().isPaused();
	}
	
	public void runCommand(DriverCommand command) {
		machineThread.scheduleRequest(new JobRequest(RequestType.RUN_COMMAND, command));
	}
	
	public void dispose() {
		if (machineThread != null) {
			machineThread.scheduleRequest(new JobRequest(RequestType.DISCONNECT_REMOTE_BUILD, null, null));
			
			// Wait 5 seconds for the thread to stop.
			try {
				machineThread.join(5000);
			} catch (Exception e) { e.printStackTrace(); }
		}
		if (driver != null) {
			driver.dispose();
		}
		if (getSimulatorDriver() != null) {
			getSimulatorDriver().dispose();
		}
		setState(new MachineState(MachineState.State.NOT_ATTACHED));
	}
	
	private Vector<MachineListener> listeners = new Vector<MachineListener>();
	
	public void addMachineStateListener(MachineListener listener) {
		listeners.add(listener);
		listener.machineStateChanged(new MachineStateChangeEvent(this,getMachineState()));
	}

	public void removeMachineStateListener(MachineListener listener) {
		listeners.remove(listener);
	}

	protected void emitStateChange(MachineState prev, MachineState current) {
		MachineStateChangeEvent e = new MachineStateChangeEvent(this, current, prev);
		Vector<MachineListener> lclone = new Vector<MachineListener>(listeners);
		for (MachineListener l : lclone) {
			l.machineStateChanged(e);
		}
	}

	protected void emitProgress(MachineProgressEvent progress) {
		for (MachineListener l : listeners) {
			l.machineProgress(progress);
		}
	}

	protected void emitToolStatus(ToolModel tool) {
		MachineToolStatusEvent e = new MachineToolStatusEvent(this, tool);
		for (MachineListener l : listeners) {
			l.toolStatusChanged(e);
		}
	}

	public int getLinesProcessed() {
		/* This is for jumping to the right line when aborting or pausing. 
		 * This way you'll have the ability to track down where to continue printing. */
		return linesProcessed;
	}
	
	// TODO: Drop this
	public boolean isSimulating() {
		return machineThread.isSimulating();
	}

	// TODO: Drop this
	public boolean isInteractiveTarget() {
		return machineThread.isInteractiveTarget();
	}
	
	// TODO: Drop this
	public JobTarget getTarget() {
		return machineThread.currentTarget;
	}
}
