package tw.fpg.com;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

import java.io.PrintStream;

import java.nio.file.Files;

import java.nio.file.StandardCopyOption;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import java.text.DateFormat;
import java.text.SimpleDateFormat;

import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class FileCopyApp {
    private static final Logger LOGGER = Logger.getLogger(FileCopyApp.class.getName());

    /**
     * @param args
     * @throws SQLException
     * @throws IOException
     */
    public static void main(String[] args) throws SQLException, IOException {
        // Load properties
        Properties p = loadProperties(args);
        List<String> requiredKeys =
            Arrays.asList("db.alias", "db.username", "db.password", "db.prgMk", "db.prgNm", "db.tbName", "delSrcMk",
                          "del.log.mk", "logPath");

        // 檢查缺少的鍵
        List<String> missingKeys = requiredKeys.stream()
                                               .filter(key -> !p.containsKey(key)) // 檢查鍵是否存在
                                               .collect(Collectors.toList());
        // Setup logging
        setupLogging(p);
        if (!missingKeys.isEmpty()) {
            System.err.println("屬性檔缺少必要屬性鍵宣告，中止執行: " + String.join(", ", missingKeys));
            System.exit(1);
        }
        String delSrcMk = p.getProperty("delSrcMk");
        String tableName = p.getProperty("db.tbName");
        LOGGER.log(Level.INFO, "連線到資料庫: {0}", p.getProperty("db.alias"));
        ConnectDb conDB = new ConnectDb(p);

        //讀取來源檔
        String sql =
            "select path_org, flnm_org, path_new, flnm_new from " + tableName + " where trntime is null order by 2";

        try (Connection conn = conDB.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            //run procedure mk = Y
            if (p.getProperty("db.prgMk").equals("Y")) {
                runProcedure(p.getProperty("db.prgNm"), conn);
            }
            while (rs.next()) {
                String srcPath = rs.getString("path_org");
                String srcFileName = rs.getString("flnm_org");
                String destPath = rs.getString("path_new");
                String destFileName = rs.getString("flnm_new");

                // 複製檔案並判斷是否成功
                boolean isCopied = copyAndRenameFile(srcPath, srcFileName, destPath, destFileName, delSrcMk);

                // 只有在檔案複製成功後才更新傳輸時間
                if (isCopied) {
                    updateTransferTime(conn, tableName, srcFileName);
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "資料庫連線時發生錯誤", e);
            System.err.println("資料庫處理錯誤:" + e.getMessage());
            throw e;
        } catch (Exception ex) // 除了SQLException以外之錯誤
        {
            LOGGER.log(Level.SEVERE, "資料庫連線以外的錯誤", ex);
            System.err.println("除了資料庫處理錯誤以外的錯誤:" + ex.getMessage());
            throw ex;
        }
        /**
         * 擷取刪除log檔註記，允許執行批次作業刪除30天之前的log檔
         * */
        if (p.getProperty("del.log.mk").equals("Y"))
            deleteLogFile(new File(p.getProperty("logPath")));
    }

    private static Properties loadProperties(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: java CopyFileApp <properties_file>");
        }
        String propFilePath = args[0];
        Properties properties = new Properties();
        try (FileInputStream fis = new FileInputStream(propFilePath)) {
            properties.load(fis);
        }
        return properties;
    }

    private static void setupLogging(Properties prop) {
        String logPath = prop.getProperty("logPath");
        String tableName = prop.getProperty("db.tbName");
        DateFormat dateFormat = new SimpleDateFormat("yyyyMMdd.HHmmss");
        Date date = new Date();
        String executedTime = dateFormat.format(date);
        String logFileName = String.format("%s/%s_%s.log", logPath, tableName, executedTime);
        try {
            File logFile = new File(logFileName);
            PrintStream logStream = new PrintStream(logFile);
            System.setOut(logStream);
            System.setErr(logStream);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    //清除30天以前的檔案(刪除log or backup目錄下的歷史檔)
    private static void deleteLogFile(File file) throws IOException {
        File[] files = file.listFiles();
        long thDay = 30L * 24 * 60 * 60 * 1000; // 30天的毫秒數

        for (File f : files) {
            long diffen = System.currentTimeMillis() - f.lastModified(); //時間差

            if (diffen >= thDay) {
                if (f.isDirectory()) {
                    deleteLogFile(f); // 先刪除目錄內的檔案
                }
                f.delete();
                System.out.println("Log目錄，超過30日的歷史檔案已刪除...." + f.getName());
            }
        }
    }

    private static boolean copyAndRenameFile(String src, String srcfile, String des, String desfile, String delSrcMk) {
        File sourceFile = new File(src, srcfile);
        File destFile = new File(des, desfile);

        try {
            // 複製檔案
            Files.copy(sourceFile.toPath(), destFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println("檔案 " + srcfile + " 複製並重命名為 " + desfile + " 成功！");

            // 刪除來源檔案
            if (delSrcMk.equals("Y")) {
                if (sourceFile.delete()) {
                    System.out.println("來源檔案 " + srcfile + " 刪除成功！");
                } else {
                    System.err.println("無法刪除來源檔案 " + srcfile + "。");
                }
            }
            return true; // 返回成功狀態
        } catch (IOException e) {
            System.err.println("檔案複製失敗: " + e.getMessage());
            return false; // 返回失敗狀態
        }
    }

    private static void runProcedure(String procedureName, Connection conn) throws SQLException {
        try (CallableStatement cstmt = conn.prepareCall("{call " + procedureName + "}")) {
            System.out.println("Procedure開始執行..." + procedureName);
            cstmt.execute();
            System.out.println("Procedure執行完畢..." + procedureName);
        } catch (SQLException e) {
            System.err.println("run Procedure Error: " + e.getMessage());
            throw e; // Re-throw the exception to let caller handle it
        }
    }

    private static void updateTransferTime(Connection conn, String tableName, String fileName) throws SQLException {
        String updateSQL = "UPDATE " + tableName + " SET TRNTIME = ? WHERE flnm_org = ?";

        try (PreparedStatement pstmt = conn.prepareStatement(updateSQL)) {
            pstmt.setTimestamp(1, new java.sql.Timestamp(System.currentTimeMillis())); // 設定當前時間
            pstmt.setString(2, fileName); // 使用來源檔案名稱來更新對應的紀錄

            int rowsUpdated = pstmt.executeUpdate();
            if (rowsUpdated > 0) {
                System.out.println("成功更新傳輸時間 for 檔案: " + fileName);
            } else {
                System.err.println("未找到對應的檔案名稱: " + fileName);
            }
        }
    }
}
