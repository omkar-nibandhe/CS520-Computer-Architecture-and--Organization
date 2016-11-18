package my.apex;

import static my.common.Helper.dispatchToIQ;
import static my.common.Helper.dispatchToLSQ;
import static my.common.Helper.dispatchToRob;
import static my.common.Helper.display;
import static my.common.Helper.echo;
import static my.common.Helper.getEntryFromROBBySlotId;
import static my.common.Helper.getInstructionObject;
import static my.common.Helper.updateRenameTable;
import static my.constants.ApplicationConstants.*;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;

import my.common.RoB;
import my.model.InstructionModel;
import my.model.InstructionType;
import my.model.RenameTable;

public class Apex {
	private static Integer PC = 20000;
	private static Integer newPCValue = 0;
	private static LinkedList<String> messages = new LinkedList<>();
	private static Map<String, Integer> registerFile = new HashMap<>();
	private static Queue<InstructionModel> ffLatch = new LinkedList<>();
	private static Queue<InstructionModel> fdLatch = new LinkedList<>();
	private static Queue<InstructionModel> iqToIntFuLatch = new LinkedList<>();
	private static Queue<InstructionModel> iqToMultFuLatch = new LinkedList<>();
	private static Queue<InstructionModel> lsqToMem1Latch = new LinkedList<>();
	private static Queue<InstructionModel> mem1Tomem2Latch = new LinkedList<>();
	private static Queue<InstructionModel> mem2Tomem3Latch = new LinkedList<>();
	private static Queue<InstructionModel> memToForwardLatch = new LinkedList<>();
	private static Queue<InstructionModel> intToForwardLatch = new LinkedList<>();
	private static Queue<InstructionModel> multToForwardLatch = new LinkedList<>();
	private static List<String> instructionList = new ArrayList<>(PC);
	private static Integer[] memoryArray = new Integer[10000];
	private static boolean isFetch1Done, isFetch2Done, isMultiplyFuFree;
	private static boolean jumpDetected = false;
	private static boolean invalidPC = false;
	private static boolean halt;
	private static List<InstructionModel> lsq = new ArrayList<>();
	private static List<InstructionModel> iqList = new ArrayList<>();
	private static Map<String, RenameTable> RenameTable = new HashMap<>();
	private static RoB rob = new RoB();
	private static int lastRobSlotID;
	private static boolean branchPredicted;

	public static void init(File file) {
		echo("\nInitialzing APEX state...");
		PC = 20000;

		instructionList = loadFile(file, PC);

		for(int i = 0; i < memoryArray.length; i++)
			memoryArray[i] = 0;

		//R0 to R7
		for(int i=0; i<8; i++) {
			String regName = "R" + i;
			registerFile.put(regName, 0);
			RenameTable rte = new RenameTable();
			rte.setSrcBit(0);
			rte.setRegisterSrc(null);
			RenameTable.put(regName, rte);
		}

		//Add X register to Register file
		registerFile.put("X", 0);
		RenameTable rte = new RenameTable();
		rte.setSrcBit(0);
		rte.setRegisterSrc(null);
		RenameTable.put("X", rte);

		isMultiplyFuFree = true;
		isFetch1Done = isFetch2Done = false;
		halt = false;
		jumpDetected = false;
		echo("\nSimulator state intialized successfully");
	}

	public static List<String> loadFile(File file, int programCounter) {
		BufferedReader buffReader = null;
		List<String> instructionList = new ArrayList<>();
		try {
			buffReader = new BufferedReader(new FileReader(file));
			String instruction;
			if(programCounter > 0) {
				for(int i = 0; i < programCounter; i++)
					instructionList.add(i, null);
			}
			while((instruction = buffReader.readLine()) != null) {
				instructionList.add(programCounter++, instruction);
			}
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			try {
				buffReader.close();
			} catch (IOException e) {
				echo("Error closing buffered reader instance");
			}
		}
		return instructionList;
	}

	public static void displaySimulationResult() {
		display(messages, registerFile, memoryArray, iqList, lsq, rob, RenameTable);
	}
	
	public static void simulate(int cycleCount) {
		int cycle = 0;
		LinkedList<String> tempList = new LinkedList<>();
		while(cycle != cycleCount) {
			if(invalidPC) {
				break;
			}

			doCommit();
			doForwarding();
			doExecution();
			doDecode();
			doFetch2();
			doFetch1();
			while(!messages.isEmpty())
				tempList.add(messages.removeLast());

			cycle++;
		}

		messages.addAll(tempList);

		if(invalidPC) {
			displaySimulationResult();
			echo("\nSimulation ended due to bad PC value..." + PC);
			System.exit(0);
		}
	}

	private static void doCommit() {
		//Check if instruction at ROB head has completed execution
		//If yes then update the destination register value in PRF
		//If not then do nothing
		int headInstructionId = rob.getHeadIndex();
		boolean isReadyToCommit = false;
		for(InstructionModel inst : rob) {
			if(inst.getRobSlotId() == headInstructionId) {
				//Cannot remove entry from ROB while iterating
				//So set a flag and remove entry at head out of iteration
				isReadyToCommit = inst.isDestReady();
				break;
			}
		}

		if(isReadyToCommit) {
			InstructionModel instruction = rob.remove();
			if(isBranchInstruction(instruction)) {
				messages.add("--");
			} else {
				int destValue = instruction.getDestinationValue();
				String destRegName = instruction.getDestRegName();
				registerFile.put(destRegName, destValue);
				updateRenameTable(instruction, RenameTable, true);
				if(STORE_STRING.equalsIgnoreCase(instruction.getOpCode().getValue())) {
					messages.add("--");
				} else {
					messages.add(destRegName + " = " + destValue);
				}
			}
		} else {
			messages.add("--");
		}
	}

	private static void doFetch1() {
		if(!ffLatch.isEmpty() || halt) {
			messages.add("--");
			return;
		}

		if(branchPredicted || jumpDetected) {
			if(PC < instructionList.size()) {
				String temp = instructionList.get(PC);
				InstructionModel instruction = getInstructionObject(temp);
				instruction.setPc(PC);
				instruction.setToBeSqaushed(true);
				instruction.setStringRepresentation(temp);
				ffLatch.add(instruction);
				PC++;
				messages.add(temp);
			} else {
				messages.add("--");
			}

			if(branchPredicted) {
				PC = newPCValue;
				branchPredicted = false;
			}

			if(jumpDetected)
				jumpDetected = false;

			return;
		}

		if(PC == instructionList.size()) {
			messages.add("--");
			return;
		}


		if(PC > instructionList.size()) {//PC is updated by BZ/BNZ/JUMP instructions
			echo("Invalid PC value detected: " + PC);
			invalidPC = true;
			return;
		}

		String temp = instructionList.get(PC);
		InstructionModel instruction = getInstructionObject(temp);
		instruction.setPc(PC);
		ffLatch.add(instruction);
		PC++;

		if(halt) {
			instruction.setToBeSqaushed(true);
		}

		instruction.setStringRepresentation(temp);
		messages.add(temp);
	}

	private static void doFetch2() {
		if(isFetch2Done && (!branchPredicted || !jumpDetected)) {
			messages.add("--");
			return;
		}

		if(!fdLatch.isEmpty()) {
			messages.add("--");
			return;
		}

		InstructionModel instruction = ffLatch.poll();
		if(instruction == null) {
			if(isFetch1Done) {
				isFetch2Done = true;
				messages.add("--");
			} else {
				messages.add("--");
			}
		} else {
			fdLatch.add(instruction);

			if(halt) {
				isFetch2Done = true;
				instruction.setToBeSqaushed(true);
			}

			if(branchPredicted || jumpDetected) {
				instruction.setToBeSqaushed(true);
			}

			messages.add(instruction.getStringRepresentation());
		}
	}

	private static void doDecode() {
		//Validations
		//Renaming
		//Dependency
		//Read values from ARF/PRF
		//Dispatch to IQ & LSQ
		if(fdLatch.isEmpty()) {
			messages.add("--");
			return;
		}
		
		InstructionModel instruction = fdLatch.poll();
		InstructionType instructionType = instruction.getOpCode();

		if(instruction.isToBeSqaushed()) {
			messages.add("--");
			return;
		}

		if(HALT_STRING.equalsIgnoreCase(instructionType.getValue())) {
			halt = true;
			messages.add("HALT");
			return;
		}

		//If instruction type is LOAD/STORE && LSQ is full then stall
		if((isLoadStoreInstruction(instructionType) && isLSQFull())) {
			messages.add("LSQ Full");
			fdLatch.add(instruction);
			return;
		}

		//If instruction type is other than LOAD/STORE && IQ is full then stall
		if(!isLoadStoreInstruction(instructionType) && isIssueQueueFull()) {
			messages.add("IQ Full");
			fdLatch.add(instruction);
			return;
		}

		boolean issueToIQ = isInstructionForIssueIQ(instructionType);

		switch (instruction.getOpCode()) {
		case MOVC:
			instruction.setSrc1Value(Integer.parseInt(instruction.getSrc1RegName()));
			instruction.setSrc1Ready(true);
			instruction.setValid(true);
			dispatch(instruction, issueToIQ);
			break;

		case MOV:
			decode(instruction, true, false, false);
			if(instruction.isSrc1Ready()) {
				instruction.setValid(true);
			}
			dispatch(instruction, issueToIQ);
			break;

		case ADD:
		case SUB:
		case MUL:
		case AND:
		case OR:
		case EX_OR:
			decode(instruction, true, true, false);
			if(instruction.isSrc1Ready() && instruction.isSrc2Ready()) {
				instruction.setValid(true);
			}
			dispatch(instruction, issueToIQ);
			break;

		case LOAD:
			decode(instruction, true, true, false);
			if(instruction.isSrc1Ready() && instruction.isSrc2Ready()) {
				instruction.setValid(true);
			}
			dispatch(instruction, issueToIQ);
			break;

		case STORE:
			decode(instruction, true, true, true);
			if(instruction.isSrc1Ready() && instruction.isSrc2Ready() && instruction.isDestReady()) {
				instruction.setValid(true);
			}
			dispatch(instruction, issueToIQ);
			break;

		case BZ:
		case BNZ:
			int offset = Integer.parseInt(instruction.getDestRegName());
			if(offset < 0) { //Predict branch will be taken for negative offsets
				newPCValue = instruction.getPc() + offset; //This actually means (PC + (-offset))
				instruction.setBranchPredictionTaken(true);
			}
			instruction.setSrc1Value(lastRobSlotID);
			instruction.setSrc1Ready(false);
			instruction.setValid(false);
			dispatch(instruction, issueToIQ);
			branchPredicted = true;
			break;

		case BAL:
		case JUMP:
			int srcValue;
			String reg = instruction.getDestRegName();
			RenameTable regDestRte = RenameTable.get(reg);
			if(regDestRte.getSrcBit() == 0) { //Read values from register file
				srcValue = registerFile.get(reg);
				instruction.setSrc1Value(srcValue);
				instruction.setSrc1Ready(true);
				instruction.setValid(true);
			} else {
				InstructionModel robInstruction = getEntryFromROBBySlotId(Integer.parseInt(regDestRte.getRegisterSrc()), rob);
				if(robInstruction.isDestReady()) {
					srcValue = robInstruction.getDestinationValue();
					instruction.setSrc1Value(srcValue);
					instruction.setSrc1Ready(true);
					instruction.setValid(true);
				} else {
					instruction.setSrc1Value(robInstruction.getRobSlotId());
					instruction.setSrc1Ready(false);
				}
			}

			if(BAL_STRING.equalsIgnoreCase(instruction.getOpCode().getValue())) {
				instruction.setDestRegName("X");
			}

			dispatch(instruction, issueToIQ);
			jumpDetected = true;
			break;

		default:
			break;
		}
		
		messages.add(instruction.getStringRepresentation());
	}

	private static boolean isLiteral(String value) {
		return (value.charAt(0) != 'R' && value.charAt(0) != 'X');
	}

	private static void decode(InstructionModel instruction, boolean decodeSrc1, boolean decodeSrc2, boolean decodeDest) {
		if(decodeSrc1) {
			String src1 = instruction.getSrc1RegName();
			if(isLiteral(src1)) {
				instruction.setSrc1Value(Integer.parseInt(src1));
				instruction.setSrc1Ready(true);
			} else {
				RenameTable regSrc1Rte = RenameTable.get(instruction.getSrc1RegName());
				int srcValue = 0;
				if(regSrc1Rte.getSrcBit() == 0) { //Read values from register file
					srcValue = registerFile.get(instruction.getSrc1RegName());
					instruction.setSrc1Value(srcValue);
					instruction.setSrc1Ready(true);
				} else {
					InstructionModel robInstruction = getEntryFromROBBySlotId(Integer.parseInt(regSrc1Rte.getRegisterSrc()), rob);
					if(robInstruction.isDestReady()) {
						srcValue = robInstruction.getDestinationValue();
						instruction.setSrc1Value(srcValue);
						instruction.setSrc1Ready(true);
					} else {
						instruction.setSrc1Value(robInstruction.getRobSlotId());
						instruction.setSrc1Ready(false);
					}
				}
			}
			
		}

		if(decodeSrc2) {
			String src2 = instruction.getSrc2RegName();
			if(isLiteral(src2)) {
				instruction.setSrc2Value(Integer.parseInt(src2));
				instruction.setSrc2Ready(true);
			} else {
				RenameTable regSrc2Rte = RenameTable.get(instruction.getSrc2RegName());
				
				int srcValue;
				if(regSrc2Rte.getSrcBit() == 0) { //Read values from register file
					srcValue = registerFile.get(instruction.getSrc2RegName());
					instruction.setSrc2Value(srcValue);
					instruction.setSrc2Ready(true);
				} else {
					InstructionModel robInstruction = getEntryFromROBBySlotId(Integer.parseInt(regSrc2Rte.getRegisterSrc()), rob);
					if(robInstruction.isDestReady()) {
						srcValue = robInstruction.getDestinationValue();
						instruction.setSrc2Value(srcValue);
						instruction.setSrc2Ready(true);
					} else {
						instruction.setSrc2Value(robInstruction.getRobSlotId());
						instruction.setSrc2Ready(false);
					}
				}
			}
		}

		if(decodeDest) {
			RenameTable regDestRte = RenameTable.get(instruction.getDestRegName());
			
			int srcValue;
			if(regDestRte.getSrcBit() == 0) { //Read values from register file
				srcValue = registerFile.get(instruction.getDestRegName());
				instruction.setDestinationValue(srcValue);
				instruction.setDestReady(true);
			} else {
				InstructionModel robInstruction = getEntryFromROBBySlotId(Integer.parseInt(regDestRte.getRegisterSrc()), rob);
				if(robInstruction.isDestReady()) {
					srcValue = robInstruction.getDestinationValue();
					instruction.setDestinationValue(srcValue);
					instruction.setDestReady(true);
				} else {
					instruction.setDestinationValue(robInstruction.getRobSlotId());
					instruction.setDestReady(false);
				}
			}
		}
	}

	private static boolean isInstructionForIssueIQ(InstructionType instructionType) {
		return !isLoadStoreInstruction(instructionType);
	}

	private static void dispatch(InstructionModel instruction, boolean issueToIQ) {
		if(issueToIQ) {
			dispatchToIQ(instruction, iqList);
		} else {
			dispatchToLSQ(instruction, lsq);
		}

		dispatchToRob(instruction, rob);

		lastRobSlotID = instruction.getRobSlotId();

		if(STORE_STRING.equalsIgnoreCase(instruction.getOpCode().getValue())) {
			//In case of store all are register are read. So no need to update Rename Table
		} else if(BZ_STRING.equalsIgnoreCase(instruction.getOpCode().getValue())
				|| BNZ_STRING.equalsIgnoreCase(instruction.getOpCode().getValue())
				|| JUMP_STRING.equalsIgnoreCase(instruction.getOpCode().getValue())) {
			//In this case also no need to updat rename table
		} else {
			updateRenameTable(instruction, RenameTable, false);
		}
		
	}

	private static void doForwarding() {
		if(!memToForwardLatch.isEmpty()) {
			forwardExecutionResults(memToForwardLatch.poll());
		} else if(!intToForwardLatch.isEmpty()) {
			forwardExecutionResults(intToForwardLatch.poll());
		} else if(!multToForwardLatch.isEmpty()) {
			forwardExecutionResults(multToForwardLatch.poll());
		}
	}

	private static boolean isBranchInstruction(InstructionModel instruction) {
		String opCode = instruction.getOpCode().getValue();
		return (BZ_STRING.equalsIgnoreCase(opCode)
				|| BNZ_STRING.equalsIgnoreCase(opCode)
				|| JUMP_STRING.equalsIgnoreCase(opCode));
	}

	private static void forwardExecutionResults(InstructionModel instruction) {
		if(isBranchInstruction(instruction)) {
			return;
		}

		for(InstructionModel robInst : rob) {
			if(!robInst.isValid()) {
				if(!robInst.isSrc1Ready()) {
					if(robInst.getSrc1Value() == instruction.getRobSlotId()) {
						robInst.setSrc1Value(instruction.getDestinationValue());
						robInst.setSrc1Ready(true);
					}
				}

				if(!robInst.isSrc2Ready()) {
					if(robInst.getSrc2Value() == instruction.getRobSlotId()) {
						robInst.setSrc2Value(instruction.getDestinationValue());
						robInst.setSrc2Ready(true);
					}
				}

				if(STORE_STRING.equalsIgnoreCase(robInst.getOpCode().getValue()) && !robInst.isDestReady()) {
					if(robInst.getDestinationValue() == instruction.getRobSlotId()) {
						robInst.setDestinationValue(instruction.getDestinationValue());
						robInst.setDestReady(true);
					}
				}

				if((robInst.getNoOfSources() == 0 || robInst.getNoOfSources() == 1) && robInst.isSrc1Ready()) {
					robInst.setValid(true);
				} else if(robInst.getNoOfSources() == 2) {
					if(STORE_STRING.equalsIgnoreCase(robInst.getOpCode().getValue())) {
						if(robInst.isSrc1Ready() && robInst.isSrc2Ready() && robInst.isDestReady())
							robInst.setValid(true);
					} else if(robInst.isSrc1Ready() && robInst.isSrc2Ready()) {
							robInst.setValid(true);
					}
				}
			}
		}
	}

	private static void doExecution() {
		doSelection();
		executeMemory3();
		executeMemory2();
		executeMemory1();
		executeMultiply();
		executeInteger();
	}

	private static void executeInteger() {
		if(iqToIntFuLatch.isEmpty()) {
			messages.add("--");
			return;
		}


		InstructionModel intInst = iqToIntFuLatch.poll();
		if(intInst.isToBeSqaushed()) {
			messages.add("--");
			return;
		}

		String opCode = intInst.getOpCode().getValue();
		int result = 0;
		switch (opCode) {
		case MOV_STRING:
		case MOVC_STRING:
			result = intInst.getSrc1Value();
			break;
		case ADD_STRING:
			result = intInst.getSrc1Value() + intInst.getSrc2Value();
			break;
		case SUB_STRING:
			result = intInst.getSrc1Value() - intInst.getSrc2Value();
			break;
		case AND_STRING:
			result = intInst.getSrc1Value() & intInst.getSrc2Value();
			break;
		case OR_STRING:
			result = intInst.getSrc1Value() | intInst.getSrc2Value();
			break;
		case EX_OR_STRING:
			result = intInst.getSrc1Value() ^ intInst.getSrc2Value();
			break;

		case BZ_STRING:
			if(intInst.getSrc1Value() == 0) {
				if(intInst.isBranchPredictionTaken()) {
					//Then it is OK
				} else {
					//Flush wrongly fetched instructions
					flushWrongPredictedInstructions(intInst);
					PC = intInst.getPc() + Integer.parseInt(intInst.getDestRegName());
				}
			} else {
				if(intInst.isBranchPredictionTaken()) {
					//Flush wrongly fetched instructions
					flushWrongPredictedInstructions(intInst);
					PC = intInst.getPc() + 1;
				} else {
					//Then it is OK
				}
			}
			break;

		case BNZ_STRING:
			if(intInst.getSrc1Value() != 0) {
				if(intInst.isBranchPredictionTaken()) {
					//Then it is OK
				} else {
					//Flush wrongly fetched instructions
					flushWrongPredictedInstructions(intInst);
					PC = intInst.getPc() + Integer.parseInt(intInst.getDestRegName());
				}
			} else {
				if(intInst.isBranchPredictionTaken()) {
					//Flush wrongly fetched instructions
					flushWrongPredictedInstructions(intInst);
					PC = intInst.getPc() + 1;
				} else {
					//Then it is OK
				}
			}
			break;

		case JUMP_STRING:
			PC = intInst.getSrc1Value() + Integer.parseInt(intInst.getSrc1RegName());
			break;

		case BAL_STRING:
			PC = intInst.getSrc1Value() + Integer.parseInt(intInst.getSrc1RegName());
			result = intInst.getPc(); // current PC value has to be saved in X register (will be done in commit)
			intInst.setDestRegName("X");
			break;

		default:
			break;
		}

		intInst.setDestinationValue(result);
		intInst.setDestReady(true);
		intInst.setValid(true);

		intToForwardLatch.add(intInst);
		messages.add(intInst.getStringRepresentation());
	}

	private static int incrementCircularQueueIndex(int currIndex) {
		if(currIndex == (rob.capacity() - 1)) {
			return 0;
		}

		return ++currIndex;
	}

	private static void flushWrongPredictedInstructions(InstructionModel branchInstruction) {
		int bzInsRobId = branchInstruction.getRobSlotId();
		bzInsRobId = incrementCircularQueueIndex(bzInsRobId);

		for(InstructionModel robInst : rob) {
			if(bzInsRobId == rob.getNextSlotIndex()) {
				break;
			}

			if(robInst.getRobSlotId() == bzInsRobId) {
				robInst.setToBeSqaushed(true);
			}

			bzInsRobId = incrementCircularQueueIndex(bzInsRobId);
		}

		//Clear instructions that are already fetched
		for(InstructionModel i : ffLatch) {
			i.setToBeSqaushed(true);
		}

		for(InstructionModel i : fdLatch) {
			i.setToBeSqaushed(true);
		}
	}

	private static void executeMultiply() {
		if(iqToMultFuLatch.isEmpty()) {
			messages.add("--");
			return;
		}

		InstructionModel mulInst = iqToMultFuLatch.poll();
		if(mulInst.isToBeSqaushed()) {
			messages.add("--");
			return;
		}

		int latencyCount = mulInst.getMultiplyLatencyCount();
		
		if(latencyCount == 0) {
			isMultiplyFuFree = false;
			int result = mulInst.getSrc1Value() * mulInst.getSrc2Value();
			mulInst.setDestinationValue(result);
		}

		latencyCount++;
		mulInst.setMultiplyLatencyCount(latencyCount);
		if(latencyCount == 4) {
			isMultiplyFuFree = true;
			mulInst.setDestReady(true);
			mulInst.setValid(true);
			multToForwardLatch.add(mulInst);
		} else {
			iqToMultFuLatch.add(mulInst);
		}

		messages.add(mulInst.getStringRepresentation());
	}

	private static void executeMemory3() {
		if(mem2Tomem3Latch.isEmpty()) {
			messages.add("--");
			return;
		}

		InstructionModel loadStoreInst = mem2Tomem3Latch.poll();
		if(loadStoreInst.isToBeSqaushed()) {
			messages.add("--");
			return;
		}

		loadStoreInst.setDestReady(true);
		loadStoreInst.setValid(true);

		if(LOAD_STRING.equalsIgnoreCase(loadStoreInst.getOpCode().getValue())) {
			int src1 = loadStoreInst.getSrc1Value();
			int src2 = loadStoreInst.getSrc2Value();
			int addr = src1 + src2;
			int value = memoryArray[addr];
			loadStoreInst.setDestinationValue(value);
			registerFile.put(loadStoreInst.getDestRegName(), value);//Update/Insert to Register file
			memToForwardLatch.add(loadStoreInst);
		} else {//Store instruction
			int src1 = loadStoreInst.getSrc1Value();
			int src2 = loadStoreInst.getSrc2Value();
			int addr = src1 + src2;
			memoryArray[addr] = loadStoreInst.getDestinationValue();//Update/Insert memory location
			//No forwarding required in case of Store
		}

		messages.add(loadStoreInst.getStringRepresentation());
	}
	
	private static void executeMemory2() {
		if(mem1Tomem2Latch.isEmpty()) {
			messages.add("--");
			return;
		}

		InstructionModel inst = mem1Tomem2Latch.poll();
		if(inst.isToBeSqaushed()) {
			messages.add("--");
			return;
		}

		mem2Tomem3Latch.add(inst);
		messages.add(inst.getStringRepresentation());
	}

	private static void executeMemory1() {
		if(lsqToMem1Latch.isEmpty()) {
			messages.add("--");
			return;
		}

		InstructionModel loadStoreInst = lsqToMem1Latch.poll();
		if(loadStoreInst.isToBeSqaushed()) {
			messages.add("--");
			return;
		}

		/*if(LOAD_INSTRUCTION.equalsIgnoreCase(loadStoreInst.getOpCode().getValue())) {
			int src1 = loadStoreInst.getSrc1Value();
			int src2 = loadStoreInst.getSrc2Value();
			int addr = src1 + src2;
			int value = MEMORY_ARRAY[addr];
			REGISTER_FILE.put(loadStoreInst.getDestRegName(), value);//Update/Insert to Register file
		} else {//Store instruction
			int src1 = loadStoreInst.getSrc1Value();
			int src2 = loadStoreInst.getSrc2Value();
			int addr = src1 + src2;
			MEMORY_ARRAY[addr] = loadStoreInst.getDestinationValue();//Update/Insert memory location
		}*/

		mem1Tomem2Latch.add(loadStoreInst);
		messages.add(loadStoreInst.getStringRepresentation());
	}

	private static void doSelection() {
		removeSquashInstructionsFromIQ();
		removeSquashInstructionsFromLSQ();

		InstructionModel integerInstruction = selectIntegerInstructionFromIQ();
		InstructionModel multiplyInstruction = selectMultiplyInstructionFromIQ();
		InstructionModel memoryInstruction = selectInstructionForExecutionFromLSQ();

		if(integerInstruction != null) {
			//Go for execution
			iqToIntFuLatch.add(integerInstruction);
		}

		if(multiplyInstruction != null) {
			//Go for execution
			iqToMultFuLatch.add(multiplyInstruction);
		}

		if(memoryInstruction != null) {
			//Go for execution
			lsqToMem1Latch.add(memoryInstruction);
		}

	}

	private static void removeSquashInstructionsFromIQ() {
		List<Integer> instToBeSquashed = new ArrayList<>();
		for(int i = 0; i < iqList.size(); i++) {
			InstructionModel inst = iqList.get(i);
			if(inst.isToBeSqaushed()) {
				instToBeSquashed.add(i);
			}
		}

		for(Integer i : instToBeSquashed) {
			iqList.remove(i);
		}
	}

	private static void removeSquashInstructionsFromLSQ() {
		List<Integer> instToBeSquashed = new ArrayList<>();
		for(int i = 0; i < lsq.size(); i++) {
			InstructionModel inst = lsq.get(i);
			if(inst.isToBeSqaushed()) {
				instToBeSquashed.add(i);
			}
		}

		for(Integer i : instToBeSquashed) {
			lsq.remove(i);
		}
	}

	private static InstructionModel selectIntegerInstructionFromIQ() {
		int instIndex = -1;
		InstructionModel selectedInstruction = null;

		for(int i = 0; i < iqList.size(); i++) {
			InstructionModel inst = iqList.get(i);
			String fuType = inst.getFuType().getValue();

			if(inst.isValid() && INTEGER_FU.equalsIgnoreCase(fuType)) {
				instIndex = i;
				break;
			}
		}

		if(instIndex != -1) {
			 selectedInstruction = iqList.remove(instIndex);
		}

		return selectedInstruction;
	}

	private static InstructionModel selectMultiplyInstructionFromIQ() {
		int instIndex = -1;
		InstructionModel selectedInstruction = null;
		for(int i = 0; i < iqList.size(); i++) {
			InstructionModel inst = iqList.get(i);
			String fuType = inst.getFuType().getValue();
			if(inst.isValid() && MULTIPLY_FU.equalsIgnoreCase(fuType) && isMultiplyFuFree) {
				instIndex = i;
				break;
			}
		}

		if(instIndex != -1) {
			 selectedInstruction = iqList.remove(instIndex);
		}

		return selectedInstruction;
	}

	private static InstructionModel selectInstructionForExecutionFromLSQ() {
		if(!lsq.isEmpty()) {
			InstructionModel inst = lsq.get(0);
			if(inst.isValid()) {
				if(STORE_STRING.equalsIgnoreCase(inst.getOpCode().getValue())) {
					if(inst.getRobSlotId() == rob.getHeadIndex()) {
						return lsq.remove(0);
					}
				} else {
					return lsq.remove(0);
				}
			}
		}
		return null;
	}

	private static boolean isLSQFull() {
		return lsq.size() == MAX_LSQ_SIZE;
	}

	private static boolean isLoadStoreInstruction(InstructionType instructionType) {
		return (LOAD_STRING.equalsIgnoreCase(instructionType.getValue())
				|| STORE_STRING.equalsIgnoreCase(instructionType.getValue()));
	}

	private static boolean isIssueQueueFull() {
		return iqList.size() == MAX_ISSUE_QUEUE_SIZE;
	}

}
