# 檔案複製專案說明

## 專案簡介

本專案是一個簡單的Java應用程式，用於從Oracle資料庫讀取檔案路徑資訊，並根據用戶的選擇將檔案從來源路徑複製到目的路徑。

## 環境設置

### 系統需求
- Java JDK 8
- Oracle資料庫
- Oracle JDBC驅動程式（ojdbc8.jar）

### 安裝指南
1. 確保已安裝Java JDK 8。
2. 創建一個名為`file_paths`的表格，包含以下欄位：
   - `path_org` (VARCHAR)
   - `flnm_org` (VARCHAR)
   - `path_new` (VARCHAR)
   - `flnm_new` (VARCHAR)

### 資料庫範例表格結構
```
CREATE TABLE file_paths (
  path_org VARCHAR(50),
  flnm_org VARCHAR(50),
  path_new VARCHAR(50),
  flnm_new VARCHAR(50)
);
```
3. 將下載的`ojdbc8.jar`文件放入您的Java專案的類路徑中。
4. 創建一個名為`config.properties`的屬性檔，內容如下：
```
# 資料庫連線設定
db.alias=dbname
db.username=username
db.password=password
#執行procedure註記
db.prgMk=N
#設定執行的procedure name
db.prgNm=
#資料來源表格名稱
db.tbName=copyfile
#清除來源檔註記，預設為N
delSrcMk=Y
#清除超過30天log
del.log.mk=Y
#log路徑
logPath=S:\\fsf001\\p0nfj1\\log\\
```
## 使用說明

### 啟動應用程式
在終端機中運行以下命令啟動檔案複製應用程式：
`java -jar fileTrans.jar`

### 主要功能操作
1. 應用程式將自動從資料庫讀取檔案路徑資訊。
2. 當目的地檔案已存在時，系統會詢問用戶是否要覆寫。
3. 根據用戶的選擇，進行相應的檔案複製操作。

## Java 程式碼範例

以下是完整的Java程式碼，用於實現上述功能：
### FileCopyApp.java
```
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
        LOGGER.log(Level.INFO, "連線到資料庫: {0}", p.getProperty("db.alias"));
        String delSrcMk = p.getProperty("delSrcMk");
        String tableName = p.getProperty("db.tbName");
        ConnectDb conDB = ConnectDb.getInstance(p);

        //讀取來源檔
        String sql = "select path_org, flnm_org, path_new, flnm_new from " + tableName + " order by 2";

        try (Connection conn = conDB.getConnection();
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            //run procedure mk = Y
            if (p.getProperty("db.prgMk").equals("Y")) {
                runProcedure(p.getProperty("db.prgNm"), conn);
            }
            while (rs.next()) {
                copyAndRenameFile(rs.getString("path_org"), rs.getString("flnm_org"), rs.getString("path_new"),
                                  rs.getString("flnm_new"), delSrcMk);
            }
        } catch (SQLException e) {
            LOGGER.log(Level.SEVERE, "資料庫連線時發生錯誤", e);
            System.err.println("資料庫處理錯誤:" + e.getMessage());
        } catch (Exception ex) // 除了SQLException以外之錯誤
        {
            LOGGER.log(Level.SEVERE, "資料庫連線以外的錯誤", ex);
            System.err.println("除了資料庫處理錯誤以外的錯誤:" + ex.getMessage());
        }
        /**
         * 擷取刪除log檔註記，允許執行批次作業刪除30天之前的log檔
         * */
        if (p.getProperty("del.log.mk").equals("Y"))
            deleteLogFile(new File(p.getProperty("logPath")));
    }

    private static Properties loadProperties(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("Usage: java MainApp <properties_file>");
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

    private static void copyAndRenameFile(String src, String srcfile, String des, String desfile, String delSrcMk) {
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
        } catch (IOException e) {
            System.err.println("檔案複製失敗: " + e.getMessage());
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
}
```

### 注意事項：
- 請根據您的環境修改`config.properties`中的使用者名稱和密碼。
- 確保在執行之前已經建立了相應的資料庫和表格結構。
- 在使用時，請確保來源路徑和目的路徑存在且可訪問。