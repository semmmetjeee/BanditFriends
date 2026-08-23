package studio.mars.banditfriends;

import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.java.JavaPlugin;
import java.sql.*;
import java.util.*;

/** Shared network store. Every operation is deliberately symmetric for friend pairs. */
final class MySqlStorage implements AutoCloseable {
  private final JavaPlugin plugin; private final String players, friends, presence; private Connection connection;
  MySqlStorage(JavaPlugin plugin) throws SQLException {
    this.plugin=plugin; var c=plugin.getConfig().getConfigurationSection("storage.mysql");
    String prefix=c.getString("table-prefix","banditfriends_"); if(!prefix.matches("[A-Za-z0-9_]+")) throw new SQLException("Invalid MySQL table-prefix");
    players=prefix+"players"; friends=prefix+"friends"; presence=prefix+"presence";
    String url="jdbc:mysql://"+c.getString("host")+":"+c.getInt("port",3306)+"/"+c.getString("database")+"?useSSL="+c.getBoolean("use-ssl",false)+"&characterEncoding=utf8";
    connection=DriverManager.getConnection(url,c.getString("username"),c.getString("password"));
    try(Statement s=connection.createStatement()) { s.executeUpdate("CREATE TABLE IF NOT EXISTS `"+players+"` (uuid CHAR(36) PRIMARY KEY, name VARCHAR(16) NOT NULL, last_seen BIGINT NOT NULL)"); s.executeUpdate("CREATE TABLE IF NOT EXISTS `"+friends+"` (owner_uuid CHAR(36) NOT NULL, friend_uuid CHAR(36) NOT NULL, friends_since BIGINT NOT NULL, PRIMARY KEY(owner_uuid, friend_uuid))"); s.executeUpdate("CREATE TABLE IF NOT EXISTS `"+presence+"` (uuid CHAR(36) PRIMARY KEY, heartbeat BIGINT NOT NULL)"); }
  }
  private Connection c() throws SQLException { if(connection==null||connection.isClosed()) throw new SQLException("MySQL connection is closed"); return connection; }
  void touch(OfflinePlayer p,long now) throws SQLException { try(PreparedStatement s=c().prepareStatement("INSERT INTO `"+players+"` (uuid,name,last_seen) VALUES (?,?,?) ON DUPLICATE KEY UPDATE name=VALUES(name),last_seen=VALUES(last_seen)")){s.setString(1,p.getUniqueId().toString());s.setString(2,p.getName()==null?"Unknown":p.getName());s.setLong(3,now);s.executeUpdate();}try(PreparedStatement s=c().prepareStatement("INSERT INTO `"+presence+"` (uuid,heartbeat) VALUES (?,?) ON DUPLICATE KEY UPDATE heartbeat=VALUES(heartbeat)")){s.setString(1,p.getUniqueId().toString());s.setLong(2,now);s.executeUpdate();} }
  boolean isNetworkOnline(UUID id,long oldestHeartbeat) throws SQLException {try(PreparedStatement s=c().prepareStatement("SELECT heartbeat FROM `"+presence+"` WHERE uuid=?")){s.setString(1,id.toString());try(ResultSet q=s.executeQuery()){return q.next()&&q.getLong(1)>=oldestHeartbeat;}}}
  Set<UUID> friends(UUID id) throws SQLException {Set<UUID> r=new HashSet<>();try(PreparedStatement s=c().prepareStatement("SELECT friend_uuid FROM `"+friends+"` WHERE owner_uuid=?")){s.setString(1,id.toString());try(ResultSet q=s.executeQuery()){while(q.next())r.add(UUID.fromString(q.getString(1)));}}return r;}
  void add(UUID a,UUID b,long since) throws SQLException {String sql="INSERT INTO `"+friends+"` (owner_uuid,friend_uuid,friends_since) VALUES (?,?,?) ON DUPLICATE KEY UPDATE friends_since=friends_since";try(PreparedStatement s=c().prepareStatement(sql)){for(UUID[] pair:List.of(new UUID[]{a,b},new UUID[]{b,a})){s.setString(1,pair[0].toString());s.setString(2,pair[1].toString());s.setLong(3,since);s.addBatch();}s.executeBatch();}}
  void remove(UUID a,UUID b) throws SQLException {try(PreparedStatement s=c().prepareStatement("DELETE FROM `"+friends+"` WHERE (owner_uuid=? AND friend_uuid=?) OR (owner_uuid=? AND friend_uuid=?)")){s.setString(1,a.toString());s.setString(2,b.toString());s.setString(3,b.toString());s.setString(4,a.toString());s.executeUpdate();}}
  long since(UUID a,UUID b) throws SQLException {try(PreparedStatement s=c().prepareStatement("SELECT friends_since FROM `"+friends+"` WHERE owner_uuid=? AND friend_uuid=?")){s.setString(1,a.toString());s.setString(2,b.toString());try(ResultSet q=s.executeQuery()){return q.next()?q.getLong(1):0;}}}
  long seen(UUID id) throws SQLException {try(PreparedStatement s=c().prepareStatement("SELECT last_seen FROM `"+players+"` WHERE uuid=?")){s.setString(1,id.toString());try(ResultSet q=s.executeQuery()){return q.next()?q.getLong(1):0;}}}
  UUID uuidByName(String name) throws SQLException {try(PreparedStatement s=c().prepareStatement("SELECT uuid FROM `"+players+"` WHERE LOWER(name)=LOWER(?) LIMIT 1")){s.setString(1,name);try(ResultSet q=s.executeQuery()){return q.next()?UUID.fromString(q.getString(1)):null;}}}
  public void close(){try{if(connection!=null)connection.close();}catch(SQLException ignored){}}
}
