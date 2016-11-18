package my.model;

import static my.constants.ApplicationConstants.*;

public enum FUTypes {

	INTEGER("Integer"),
	MULTIPLY("Multiply"),
	MEMORY("Memory");

	private String value;

	FUTypes(String type) {
		this.value = type;
	}

	public String getValue() {
		return this.value;
	}

	public static FUTypes getFunctionalUnitType(String type) {
		switch (type) {
		case ADD_STRING:
		case SUB_STRING:
		case MOV_STRING:
		case MOVC_STRING:
		case EX_OR_STRING:
		case AND_STRING:
		case OR_STRING:
		case HALT_STRING:
		case BZ_STRING:
		case BNZ_STRING:
		case JUMP_STRING:
		case BAL_STRING:
			return INTEGER;

		case LOAD_STRING:
		case STORE_STRING:
			return MEMORY;

		case MUL_STRING:
			return MULTIPLY;

		default:
			break;
		}
		return null;
	}

}
