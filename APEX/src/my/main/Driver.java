package my.main;
import static my.common.Helper.displaySimulatorMenu;
import static my.constants.ApplicationConstants.DISPLAY;
import static my.constants.ApplicationConstants.EXIT;
import static my.constants.ApplicationConstants.INITIALIZE;
import static my.constants.ApplicationConstants.SIMULATE;

import java.io.File;
import java.util.Scanner;

import my.apex.Apex;

public class Driver {

	public static void main(String[] args) {
		if(args.length == 0) {
			System.out.println("Instruction file name absent!!!");
			System.exit(1);
		}

		Scanner scan = new Scanner(System.in);
		File file = new File(args[0]);

		while(true) {
			displaySimulatorMenu();
			int option = scan.nextInt();

			switch (option) {
			case INITIALIZE:
				Apex.init(file);
				break;

			case SIMULATE:
				System.out.print("Enter number of cycles : ");
				int cycleCount = scan.nextInt();
				Apex.simulate(cycleCount);
				break;

			case DISPLAY:
				Apex.displaySimulationResult();
				break;

			case EXIT:
				scan.close();
				System.exit(0);
				break;

			default:
				break;
			}
		}
	}

}
