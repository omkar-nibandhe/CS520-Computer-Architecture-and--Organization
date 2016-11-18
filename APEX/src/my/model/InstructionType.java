package my.model;

import static my.constants.ApplicationConstants.*;

public enum InstructionType {

	BZ("BZ"),
	OR("OR"), 
	BNZ("BNZ"),
	AND("AND"),
	MOV("MOV"),
	ADD("ADD"),
	SUB("SUB"),
	MUL("MUL"),
	BAL("BAL"),
	HALT("HALT"),
	MOVC("MOVC"),
	JUMP("JUMP"),
 	LOAD("LOAD"),
	EX_OR("EX-OR"),
	STORE("STORE"),
	SQUASH("SQUASH");

	private String value;

	InstructionType(String val) {
		value = val;
	}

	public String getValue() {
		return this.value;
	}

	public static InstructionType getInstructionType(String instruction) {
		String type = instruction.split(" ")[0];
		switch (type) {
		case BZ_STRING:
			return BZ;

		case OR_STRING:
			return OR;

		case BNZ_STRING:
			return BNZ;

		case AND_STRING:
			return AND;
			
		case MOV_STRING:
			return MOV;
			
		case ADD_STRING:
			return ADD;
			
		case SUB_STRING:
			return SUB;
			
		case MUL_STRING:
			return MUL;
			
		case BAL_STRING:
			return BAL;
			
		case HALT_STRING:
			return HALT;
		
		case MOVC_STRING:
			return MOVC;
		
		case JUMP_STRING:
			return JUMP;
			
		case LOAD_STRING:
			return LOAD;
			
		case STORE_STRING:
			return STORE;
			
		case EX_OR_STRING:
			return EX_OR;
			
		case SQUASH_INSTRUCTION:
			return SQUASH;
			
		default:
			return null;
		}
	}
}
