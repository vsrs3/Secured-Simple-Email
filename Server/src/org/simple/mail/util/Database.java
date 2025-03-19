package org.simple.mail.util;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;

public class Database {
	public final static String DB_NAME = "maildb";
	public final static String ACCOUNT = "mailadmin";
	public final static String PASSWORD = "123456";	
	private final static String URL_PREFIX = "jdbc:mysql://localhost:6688/";
	private Connection conn;
	Statement stmt;
	
	public Database(String dbName, String account, String password) throws SQLException{
		StringBuilder url = new StringBuilder(URL_PREFIX);
		url.append(dbName);
		conn = DriverManager.getConnection(url.toString(), account, password);
		stmt = conn.createStatement();
	}
	
	public int insertMail(Mail mail){
		int ret = 0;
		String query = "INSERT INTO tbl_mails (date, sender, recipient, body) VALUES (?, ?, ?, ?);";
		try(PreparedStatement insertStmt = conn.prepareStatement(query)
		){
			DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss");
			String timeString = dateFormat.format(mail.getReceivedTime());
			
			insertStmt.setTimestamp(1, Timestamp.valueOf(timeString));
			insertStmt.setString(2, mail.getSender());
			insertStmt.setString(3, mail.getRecipient());
			insertStmt.setString(4, mail.getBody());
			
			ret = insertStmt.executeUpdate();
		}catch(SQLException e){
			e.printStackTrace();
		}
		return ret;
	}

	public Mail retrieveMail(String recipient, int id) {
		Mail mail = null;
		String query = "SELECT * FROM tbl_mails WHERE recipient = ? AND id = ?;";

		try (PreparedStatement selectStmt = conn.prepareStatement(query)) {
			selectStmt.setString(1, recipient);
			selectStmt.setInt(2, id);

//			System.out.println("Executing query: " + selectStmt.toString());

			ResultSet rs = selectStmt.executeQuery();

			if (rs.next()) {  // Changed from rs.first() to rs.next()
				mail = new Mail();
				mail.setId(rs.getInt("id"));
				mail.setSender(rs.getString("sender"));
				mail.setRecipient(rs.getString("recipient"));
				mail.setBody(rs.getString("body"));
				mail.setTime(rs.getTimestamp("date"));
				System.out.println("Mail found with ID: " + id);
			} else {
				System.out.println("No mail found with ID: " + id + " for recipient: " + recipient);
			}
		} catch (SQLException e) {
			System.err.println("Error retrieving mail: " + e.getMessage());
			e.printStackTrace();
		}

		return mail;
	}
	
	public ArrayList<Mail> retrieveMailList(String recipient){
		ArrayList<Mail> list = new ArrayList<Mail>();
		//String query = "SELECT id, sender, recipient, date FROM tbl_mails WHERE recipient = ?;";
		String query = "SELECT * FROM tbl_mails WHERE recipient = ?;";
		try(PreparedStatement selectStmt = conn.prepareStatement(query)
		){
			selectStmt.setString(1, recipient);			
			ResultSet rs = selectStmt.executeQuery();
			if(rs.next()){
				Mail mail = new Mail();
				do{
					mail = new Mail();
					mail.setId(rs.getInt("id"));
					mail.setSender(rs.getString("sender"));
					mail.setRecipient(rs.getString("recipient"));
					mail.setTime(rs.getTimestamp("date"));
					list.add(mail);					
				}while (rs.next());
			}				
		}catch(SQLException e){
			e.printStackTrace();
		}
		return list;
	}
	
	public int deleteMail(String recipient, int id){
		int ret = 0;
		String query = "DELETE FROM tbl_mails WHERE recipient = ? AND id = ?;";
		try(PreparedStatement deleteStmt = conn.prepareStatement(query)
		){
			deleteStmt.setString(1, recipient);
			deleteStmt.setInt(2, id);
			ret = deleteStmt.executeUpdate();		
		}catch(SQLException e){
			e.printStackTrace();
		}
		return ret;
	}
}
