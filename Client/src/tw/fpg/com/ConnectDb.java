package tw.fpg.com;

import java.sql.Connection;
import java.sql.SQLException;

import java.util.Properties;

import oracle.jdbc.pool.OracleDataSource;

public class ConnectDb {
    private String dbName;
    private String user;
    private String password;
    final static String TNSNAME = "C:/app/Administrator/product/11.2.0/client_1/network/admin";

    public ConnectDb(Properties prop) {
        super();
        this.user = prop.getProperty("db.username");
        this.password = prop.getProperty("db.password");
        this.dbName = prop.getProperty("db.alias");
    }

    public Connection getConnection() throws SQLException {
        System.setProperty("oracle.net.tns_admin", TNSNAME);
        OracleDataSource ods = new OracleDataSource();
        ods.setUser(user);
        ods.setPassword(password);
        ods.setTNSEntryName(dbName);
        ods.setDriverType("thin");
        Connection conn = ods.getConnection();
        conn.setClientInfo("OCSID.ACTION", "CopyFileApp");

        return conn;
    }
}
