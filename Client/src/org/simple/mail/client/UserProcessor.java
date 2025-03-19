package org.simple.mail.client;

import org.simple.mail.util.*;

import javax.crypto.SecretKey;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.security.PrivateKey;
import java.security.PublicKey;

public class UserProcessor {
	private Socket socket;
	private Request request;
	private Response response;
	private TcpChannel channel;

	public UserProcessor(Socket sock){
		this.socket = sock;
		try {
			channel = new TcpChannel(socket);
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	public int process() throws IOException {
		String command = request.getCommand();

		// Handle DATA command with the new secure approach
		if (command.compareToIgnoreCase(Command.DATA) == 0) {
			return handleSecureDataCommand();
		}

		// For all other commands, use the regular approach
		channel.sendRequest(request);
		response = channel.receiveResponse();

		if (response != null) {
			handleResponse(command);
			return 0;
		} else {
			return -1;
		}
	}

	/**
	 * Handles the DATA command securely by:
	 * 1. Collecting email content first
	 * 2. Encrypting and signing it
	 * 3. Only sending to the server if all security steps succeed
	 *
	 * This prevents unencrypted emails from being sent to the server
	 * when there are issues with certificates or keys.
	 */
	private int handleSecureDataCommand() throws IOException {
		BufferedReader user = new BufferedReader(new InputStreamReader(System.in));

		// First collect the email content (not sending DATA command yet)
		System.out.println("Enter email content, end with \".\" on a line by itself:");
		StringBuilder emailContent = new StringBuilder();
		String line;

		// Collect email content until "." is entered
		do {
			line = user.readLine();
			if (!line.equals(Mail.END_MAIL)) {
				emailContent.append(line).append("\n");
			}
		} while (!line.equals(Mail.END_MAIL));

		// Now that we have the content, try to encrypt it
		try {
			// Ask for recipient's public key certificate
			System.out.print("Enter path to recipient's certificate: ");
			String recipientCertPath = user.readLine();

			if (!new java.io.File(recipientCertPath).exists()) {
				System.err.println("Error: Certificate file not found!");
				System.out.println("Email was not sent. Please try again.");
				return 0; // Return without sending anything to the server
			}

			// Load recipient's public key
			PublicKey recipientPublicKey;
			try {
				recipientPublicKey = CryptoUtils.loadPublicKey(recipientCertPath);
			} catch (Exception e) {
				System.err.println("Error: Cannot load recipient's certificate: " + e.getMessage());
				System.out.println("Email was not sent. Please try again.");
				return 0; // Return without sending anything to the server
			}

			// Ask for sender's private key
			System.out.print("Enter path to your private key: ");
			String senderPrivateKeyPath = user.readLine();

			if (!new java.io.File(senderPrivateKeyPath).exists()) {
				System.err.println("Error: Private key file not found!");
				System.out.println("Email was not sent. Please try again.");
				return 0; // Return without sending anything to the server
			}

			// Ask for private key password
			System.out.print("Enter password for your private key: ");
			char[] password = user.readLine().toCharArray();

			// Load sender's private key
			PrivateKey senderPrivateKey;
			try {
				senderPrivateKey = CryptoUtils.loadPrivateKey(senderPrivateKeyPath, password);
			} catch (Exception e) {
				System.err.println("Error: Cannot load private key. Wrong password or invalid key format: " + e.getMessage());
				System.out.println("Email was not sent. Please try again.");
				return 0; // Return without sending anything to the server
			}

			System.out.println("Encrypting and signing email...");

			// Generate random AES key (256 bits)
			SecretKey aesKey = CryptoUtils.generateAESKey(256);

			// Generate random IV for AES
			byte[] iv = CryptoUtils.generateIV();

			// Encrypt email content with AES
			byte[] encryptedContent = CryptoUtils.encryptAES(
					emailContent.toString().getBytes(), aesKey, iv);

			// Encrypt AES key with recipient's public key
			byte[] encryptedKey = CryptoUtils.encryptRSA(aesKey.getEncoded(), recipientPublicKey);

			// Sign the encrypted content
			byte[] signature = CryptoUtils.sign(encryptedContent, senderPrivateKey);

			// Create secure email
			SecureEmail secureEmail = new SecureEmail(
					CryptoUtils.encodeBase64(signature),
					CryptoUtils.encodeBase64(iv),
					CryptoUtils.encodeBase64(encryptedKey),
					CryptoUtils.encodeBase64(encryptedContent)
			);

			// Email encryption and signing successful, now send DATA command
			Request dataRequest = new Request(Command.DATA);
			channel.sendRequest(dataRequest);
			response = channel.receiveResponse();

			if (response == null || !response.getCode().equals(Response.SUCCESS)) {
				System.out.println("Server rejected DATA command: " +
						(response != null ? response.craftToString() : "No response"));
				return 0;
			}

			System.out.println("Receive: " + response.craftToString());

			// Now send the secure email line by line
			String[] secureEmailLines = secureEmail.toString().split("\n");
			for (String emailLine : secureEmailLines) {
				channel.sendRequest(new Request(emailLine));
			}

			// End the email with "."
			channel.sendRequest(new Request(Mail.END_MAIL));

			// Receive and display the server response
			response = channel.receiveResponse();
			System.out.println("Receive: " + response.craftToString());
			return 0;

		} catch (Exception e) {
			System.err.println("Error securing email: " + e.getMessage());
			e.printStackTrace();
			System.out.println("Email was not sent due to encryption errors. Please try again.");
			return 0; // Return without sending anything to the server
		}
	}

	public void setResponse(Response res){
		this.response = res;
	}

	public void setRequest(Request req){
		this.request = req;
	}

	private void handleResponse(String command) throws IOException{
		System.out.println("Receive: " + response.craftToString());

		String returnCode = response.getCode();
		if (returnCode.compareTo(Response.SUCCESS) == 0){
			// Remove special handling for DATA as it's now handled in process()
			if (command.compareToIgnoreCase(Command.LIST) == 0)
				doListResponse();
			else if (command.compareToIgnoreCase(Command.RETRIEVE) == 0)
				doRetrieveResponse();
		}
	}

	// No longer needed as we handle DATA in a different way
	private void doDataResponse() throws IOException {
		// This method is kept for backward compatibility but should not be called
		System.err.println("ERROR: doDataResponse() called directly. This should not happen.");
	}

	private void doListResponse() throws IOException{
		StringBuilder builder = new StringBuilder();
		int numberOfMail = Integer.parseInt(response.getNotice());
		for(int i = 0; i < numberOfMail; i++)
			builder.append(channel.receiveLine());
		System.out.println(builder.toString());
	}

	private void doRetrieveResponse() throws IOException {
		StringBuilder emailBuilder = new StringBuilder();
		String line;
		int leftBytes = Integer.parseInt(response.getNotice()) + 1;

		// Read the entire email
		while (leftBytes > 0) {
			line = channel.receiveLine();
			emailBuilder.append(line);
			leftBytes = leftBytes - line.length();
		}

		String rawEmail = emailBuilder.toString();

		// Check if this is a secure email
		if (SecureEmail.isSecureEmail(rawEmail)) {
			try {
				// Parse the secure email
				SecureEmail secureEmail = SecureEmail.parseFromString(rawEmail);

				BufferedReader user = new BufferedReader(new InputStreamReader(System.in));

				// Ask for sender's public key certificate
				System.out.print("Enter path to sender's certificate: ");
				String senderCertPath = user.readLine();

				if (!new java.io.File(senderCertPath).exists()) {
					System.err.println("Error: Certificate file not found!");
					System.out.println("\nRaw Email (verification failed):");
					System.out.println("------------------------------");
					System.out.println(rawEmail);
					return;
				}

				// Load sender's public key
				PublicKey senderPublicKey;
				try {
					senderPublicKey = CryptoUtils.loadPublicKey(senderCertPath);
				} catch (Exception e) {
					System.err.println("Error: Cannot load sender's certificate: " + e.getMessage());
					System.out.println("\nRaw Email (verification failed):");
					System.out.println("------------------------------");
					System.out.println(rawEmail);
					return;
				}

				// Decode signature and encrypted content for verification
				byte[] signature = CryptoUtils.decodeBase64(secureEmail.getBase64Signature());
				byte[] encryptedContent = CryptoUtils.decodeBase64(secureEmail.getBase64EncryptedContent());

				// Verify the signature
				boolean signatureValid = false;
				try {
					signatureValid = CryptoUtils.verifySignature(encryptedContent, signature, senderPublicKey);
				} catch (Exception e) {
					System.err.println("Error verifying signature: " + e.getMessage());
					System.out.println("\nRaw Email (verification failed):");
					System.out.println("------------------------------");
					System.out.println(rawEmail);
					return;
				}

				if (signatureValid) {
					System.out.println("Signature verification successful! Email is authentic.");

					// Continue with decryption

					// Ask for recipient's private key
					System.out.print("Enter path to your private key: ");
					String recipientPrivateKeyPath = user.readLine();

					if (!new java.io.File(recipientPrivateKeyPath).exists()) {
						System.err.println("Error: Private key file not found!");
						System.out.println("\nRaw Email (cannot decrypt):");
						System.out.println("------------------------------");
						System.out.println(rawEmail);
						return;
					}

					// Ask for private key password
					System.out.print("Enter password for your private key: ");
					char[] password = user.readLine().toCharArray();

					// Load recipient's private key
					PrivateKey recipientPrivateKey;
					try {
						recipientPrivateKey = CryptoUtils.loadPrivateKey(recipientPrivateKeyPath, password);
					} catch (Exception e) {
						System.err.println("Error: Cannot load private key. Wrong password or invalid key format: " + e.getMessage());
						System.out.println("\nRaw Email (cannot decrypt):");
						System.out.println("------------------------------");
						System.out.println(rawEmail);
						return;
					}

					try {
						System.out.println("Decrypting email...");

						// Decrypt the AES key using recipient's private key
						byte[] encryptedKey = CryptoUtils.decodeBase64(secureEmail.getBase64EncryptedKey());
						byte[] aesKeyBytes = CryptoUtils.decryptRSA(encryptedKey, recipientPrivateKey);
						SecretKey aesKey = CryptoUtils.createSecretKey(aesKeyBytes);

						// Decrypt the email content
						byte[] iv = CryptoUtils.decodeBase64(secureEmail.getBase64IV());
						byte[] decryptedContent = CryptoUtils.decryptAES(encryptedContent, aesKey, iv);
						String decryptedEmail = new String(decryptedContent);

						// Display the decrypted email
						System.out.println("\nDecrypted Email:");
						System.out.println("----------------");
						System.out.println(decryptedEmail);
					} catch (Exception e) {
						System.err.println("Error decrypting email: " + e.getMessage());
						System.out.println("\nRaw Email (decryption failed):");
						System.out.println("------------------------------");
						System.out.println(rawEmail);
					}
				} else {
					System.err.println("Signature verification failed! Email may have been tampered with.");
					System.out.println("\nRaw Email (invalid signature):");
					System.out.println("------------------------------");
					System.out.println(rawEmail);
				}
			} catch (Exception e) {
				System.err.println("Error processing secure email: " + e.getMessage());
				System.out.println("\nRaw Email (processing error):");
				System.out.println("------------------------------");
				System.out.println(rawEmail);
			}
		} else {
			// This is a regular, unencrypted email
			System.out.println("Regular (unencrypted) email received:");
			System.out.println("------------------------------------");
			System.out.println(rawEmail);
		}
	}
}