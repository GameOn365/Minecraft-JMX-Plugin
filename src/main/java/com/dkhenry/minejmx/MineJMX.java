package com.dkhenry.minejmx;

import java.io.File;
import java.io.IOException;
import java.lang.Class;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.UnknownHostException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Logger;

import javax.management.InstanceAlreadyExistsException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.MalformedObjectNameException;
import javax.management.NotCompliantMBeanException;
import javax.management.ObjectName;
import javax.management.remote.JMXAuthenticator;
import javax.management.remote.JMXConnectorServer;
import javax.management.remote.JMXConnectorServerFactory;
import javax.management.remote.JMXPrincipal;
import javax.management.remote.JMXServiceURL;
import javax.security.auth.Subject;

import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.* ;

public class MineJMX extends JavaPlugin {

	Logger log = Logger.getLogger("Minecraft") ;

	/* The Listener Classes */
	private final MineJMXBlockListener blockListener = new MineJMXBlockListener(this) ;
	private final MineJMXPlayerListener playerListener = new MineJMXPlayerListener(this) ;
	//private final MineJMXServerListener serverListener = new MineJMXServerListener(this) ;
	private final MineJMXEntityListener entityListener = new MineJMXEntityListener(this) ;

	/* The JMx Specific Variables */
	private MBeanServer mbs ;
	private JMXConnectorServer cs ;
	private Registry reg ;

	/* The MBeans and their containers */
	public ServerData serverData ;
	public ServerPerformanceData serverPerformanceData ;
	public Map<String,PlayerData> playerData ;
	public Map<String,BlockData> blockData ;
	public Map<String,NpeData> npeData;

	/* The Configure variables */
	private String username = "admin";
	private String passwd = "passwd";
	private int port = 9999;
	private String ip = "*";	
	private String hostname = null ; 
	
	private static String dir = "plugins";

	private static String Persistance = dir + File.separator + "MineJMX.db" ;


	/* Class to enable Password Based JMx Authentication */
	private class JmxAuthenticatorImple implements JMXAuthenticator {

		@Override
		public Subject authenticate(Object credentials) {
			String[] info = (String[]) credentials ;
			if( info[0].equals(username) && info[1].equals(passwd)) {
				Subject s = new Subject() ;
				s.getPrincipals().add(new JMXPrincipal(info[0])) ;
				return s ;
			} else {
				throw new SecurityException() ;
			}
		}

	}

	private JmxAuthenticatorImple auth = new JmxAuthenticatorImple() ;	

	private ServerTickPoller tickPoller;

	/**
	 * @brief This Function handles Loading the Configuration
	 */
	private void loadConfig() {
		/* Read in the Properties File */
                FileConfiguration cfg = this.getConfig();
                cfg.addDefault("username", "admin");
                cfg.addDefault("password", "passwd123");
                cfg.addDefault("port", 9999);
                cfg.addDefault("ip", "*");                
                cfg.options().copyDefaults(true);
                this.saveConfig();
                            
                this.username = cfg.getString("username");
                if(cfg.get("password").getClass().equals(Integer.class)) {
                    // All-numeric password needs to be converted to String.
                    int ipass = cfg.getInt("password");
                    this.passwd = Integer.toString(ipass);
                }
                else {
                    this.passwd = cfg.getString("password");
                }
                this.port = cfg.getInt("port");
                this.ip = cfg.getString("ip");
                if( cfg.contains("hostname") ) { 
                	this.hostname = cfg.getString("hostname") ;
                }
                

	}

	private void prepTables(Statement stat) throws SQLException {
		stat.execute("CREATE TABLE IF NOT EXISTS metrics ( key , type , data , PRIMARY KEY(key,type) );") ;
	}

	private void loadState() {
		try {
			Class.forName("org.sqlite.JDBC");
			Connection conn = DriverManager.getConnection("jdbc:sqlite:"+MineJMX.Persistance );
			Statement stat = conn.createStatement();
			prepTables(stat) ;
			ResultSet rs = stat.executeQuery("SELECT key , type , data FROM metrics ;") ;

			while (rs.next()) {
				log.info("Restoring : " + rs.getString("key") + ":" + rs.getString("type") + ":" + rs.getString("data")) ;
				if(rs.getString("type").equals("server")) {
					this.serverData = ServerData.instanceFromResultSet(rs, this) ;
				} else if(rs.getString("type").equals("player")) {
					PlayerData pd = PlayerData.instanceFromResultSet(rs, this) ;
					this.addPlayer(rs.getString("key"), pd) ;
				} else if(rs.getString("type").equals("block")) {
					BlockData bd = BlockData.instanceFromResultSet(rs, this) ;
					this.addBlock(rs.getString("key"), bd) ;
				} else if(rs.getString("type").equals("npe")) {
					NpeData nd = NpeData.instanceFromResultSet(rs, this);
					this.addNpe(rs.getString("key"), nd);
				} else if(rs.getString("type").equals("performance")) {
					this.serverPerformanceData = ServerPerformanceData.instanceFromResultSet(rs, this) ;
				}
			}
			rs.close();
			conn.close();
		} catch (ClassNotFoundException e) {
			 e.printStackTrace();
		} catch (SQLException e) {
			 e.printStackTrace();
		}
	}

	private void saveState() {
		try {
			Class.forName("org.sqlite.JDBC");
			Connection conn = DriverManager.getConnection("jdbc:sqlite:"+MineJMX.Persistance );
			Statement stat = conn.createStatement();
			prepTables(stat) ;
			ResultSet rs = stat.executeQuery("SELECT key , type , data FROM metrics ;") ;
			for(Entry<String, BlockData> entry : this.blockData.entrySet()) {
				BlockData d = entry.getValue() ;
				log.info("Saving: "+entry.getKey()+" : "+d.getMetricData()) ;
				stat.executeUpdate("INSERT OR REPLACE INTO metrics VALUES ('"+entry.getKey()+"', 'block' , '"+d.getMetricData()+"') ;") ;
			}
			for(Entry<String, PlayerData> entry : this.playerData.entrySet()) {
				PlayerData d = entry.getValue() ;
				log.info("Saving: "+entry.getKey()+" : "+d.getMetricData()) ;
				stat.executeUpdate("INSERT OR REPLACE INTO metrics VALUES ('"+entry.getKey()+"', 'player' , '"+d.getMetricData()+"') ;") ;
			}
			for(Entry<String, NpeData> entry : this.npeData.entrySet()) {
				NpeData d = entry.getValue();
				log.info("Saving: " + entry.getKey() + " : " + d.getMetricData());
				stat.executeUpdate("INSERT OR REPLACE INTO metrics VALUES ('" + entry.getKey() + "', 'npe', '" + d.getMetricData() + "');");
			}
			log.info("Saving: this : server : "+this.serverData.getMetricData()) ;
			stat.executeUpdate("INSERT OR REPLACE INTO metrics VALUES ('this' , 'server' , '"+this.serverData.getMetricData()+"') ;") ;

			stat.executeUpdate("INSERT OR REPLACE INTO metrics VALUES ('this' , 'performance' , '"+this.serverPerformanceData.getMetricData()+"') ;") ;

			rs.close();
			conn.close();
		} catch (ClassNotFoundException e) {
			 e.printStackTrace();
		} catch (SQLException e) {
			 e.printStackTrace();
		}
	}
	/**
	 * @brief Since we don't want to make everyone modify their start script
	 * To Enable JMX we will do it programaticly
	 */
	private void enableJMX() {
		/* Enable the JMX Portion of the System */
		//acquiring platform MBeanServer

		// Set the hostname if we need to 
		if(null != this.hostname) {
			log.info("MineJMX: Using Minecraft Server hostname of: " + this.hostname) ;
			System.setProperty("java.rmi.server.hostname", this.hostname) ; 
		}
		
		mbs = ManagementFactory.getPlatformMBeanServer();
		if( null == mbs ) {
			log.info("Platform MBean Server isnull creating a new mbs") ;
			mbs = MBeanServerFactory.createMBeanServer();
		}

		//creating JMXConnectorServer instance
		JMXServiceURL url;
		
		try {
			String addr = "127.0.0.1" ;
			if ( this.ip.equals("")) {
				addr = Bukkit.getServer().getIp() ;
				log.info("MineJMX: Using Minecraft Server IP of: " + addr) ;
			} else if (this.ip.equals("*")) {
				addr = InetAddress.getLocalHost().getHostAddress() ;
				log.info("MineJMX: Using localhostname IP of: " + addr) ;
			} else {
				addr = this.ip ;
				log.info("MineJMX: Using Configured IP of: " + addr) ;
			}
						
			url = new JMXServiceURL("service:jmx:rmi:///jndi/rmi://"
			      + addr + ":" + this.port + "/jmxrmi");
			Map<String, Object> env = new HashMap<String,Object>() ;
			env.put(JMXConnectorServer.AUTHENTICATOR, auth) ;				
			log.info("Registering JMX Server On: " + url.toString()) ;
			cs = JMXConnectorServerFactory.newJMXConnectorServer(url, env , mbs);
			reg = LocateRegistry.createRegistry(this.port);

			//starting JMXConnectorServer
			cs.start();

		} catch (MalformedURLException e) {
			e.printStackTrace();
		} catch (UnknownHostException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * @brief This will add a new player and register him with the MBeanServer
	 * @param name The name of the player
	 * @param player The PlayerData Object or NULL to have one automatically created
	 */
	public void addPlayer(String name , PlayerData player) {
		if( player == null ) {
			player = new PlayerData(this) ;
		}
		// Register the MBean
		ObjectName oName ;
		try {
			oName = new ObjectName("org.dkhenry.minejmx:type=PlayerData,name="+name);
			if( mbs.isRegistered(oName) ) {
				mbs.unregisterMBean(oName) ;
			}
			mbs.registerMBean(player, oName) ;
		} catch (InstanceAlreadyExistsException e) {
			e.printStackTrace();
		} catch (MBeanRegistrationException e) {
			e.printStackTrace();
		} catch (NotCompliantMBeanException e) {
			e.printStackTrace();
		} catch (MalformedObjectNameException e) {
			e.printStackTrace();
		} catch (NullPointerException e) {
			e.printStackTrace();
		} catch (InstanceNotFoundException e) {
			e.printStackTrace();
		}

		this.playerData.put(name, player) ;
	}

	public void addBlock(String mat, BlockData blockData) {
		String name = mat ;
		if( blockData == null ) {
			blockData = new BlockData(this) ;
		}
		// Register the MBean
		ObjectName oName ;
		try {
			oName = new ObjectName("org.dkhenry.minejmx:type=BlockData,name="+name);
			if( mbs.isRegistered(oName) ) {
				mbs.unregisterMBean(oName) ;
			}
			mbs.registerMBean(blockData, oName) ;
		} catch (InstanceAlreadyExistsException e) {
			//e.printStackTrace();
		} catch (MBeanRegistrationException e) {
			//e.printStackTrace();
		} catch (NotCompliantMBeanException e) {
			//e.printStackTrace();
		} catch (MalformedObjectNameException e) {
			//e.printStackTrace();
		} catch (NullPointerException e) {
			//e.printStackTrace();
		} catch (InstanceNotFoundException e) {
			//e.printStackTrace();
		}

		this.blockData.put(name, blockData) ;
	}

	public void addNpe(String className, NpeData data) {
		String name = className;
		if(data == null) {
			data = new NpeData(this);
		}
		// Register the MBean
		ObjectName oName;
		try {
			oName = new ObjectName("org.dkhenry.minejmx:type=NpeData,name=" + name);
			if( mbs.isRegistered(oName) ) {
				mbs.unregisterMBean(oName) ;
			}
			mbs.registerMBean(data, oName) ;
		} catch (InstanceAlreadyExistsException e) {
			//e.printStackTrace();
		} catch (MBeanRegistrationException e) {
			//e.printStackTrace();
		} catch (NotCompliantMBeanException e) {
			//e.printStackTrace();
		} catch (MalformedObjectNameException e) {
			//e.printStackTrace();
		} catch (NullPointerException e) {
			//e.printStackTrace();
		} catch (InstanceNotFoundException e) {
			//e.printStackTrace();
		}

		this.npeData.put(name, data) ;
	}

	public PlayerData getPlayerData(String name, String logIfNotFound) {
		PlayerData playerData;
		if(this.playerData.containsKey(name)) {
			return this.playerData.get(name);
		}
		if(logIfNotFound.length() > 0) {
			this.log.info(logIfNotFound);
		}
		playerData = new PlayerData(this);
		this.addPlayer(name, playerData);
		return playerData;
	}

	public BlockData getBlockData(String mat, String logIfNotFound) {
		BlockData blockData;
		if(this.blockData.containsKey(mat)) {
			return this.blockData.get(mat);
		}
		if(logIfNotFound.length() > 0) {
			this.log.info(logIfNotFound);
		}
		blockData = new BlockData(this);
		this.addBlock(mat, blockData);
		return blockData;
	}

	public NpeData getNpeData(String type, String logIfNotFound) {
		NpeData npeData;
		if(this.npeData.containsKey(type)) {
			return this.npeData.get(type);
		}
		if(logIfNotFound.length() > 0) {
			this.log.info(logIfNotFound);
		}
		npeData = new NpeData(this);
		this.addNpe(type, npeData);
		return npeData;
	}

	public NpeData getNpeDataByClass(Class that) {
		String name = MineJMX.getSimpleClassName(that);
		if(name.startsWith("Craft")) {
			name = name.substring(5);
		}
		return this.getNpeData(name, "MineJMX is seeing non-player Entity type \"" + name + "\" for the first time.");
	}

	@Override
	public void onDisable() {
		saveState() ;
		//stopping JMXConnectorServer
		try {
			cs.stop();
			java.rmi.server.UnicastRemoteObject.unexportObject(reg,true);
		} catch (IOException e) {
			e.printStackTrace();
		}
		log.info("The MineJMX Plugin has been disabled.") ;
	}

	@Override
	public void onEnable() {
		/* Load our Configuration File */
		loadConfig() ;

		/* Do the Magic to Enable JMX  */
		enableJMX() ;

		this.serverData = new ServerData(this);
		this.serverPerformanceData = new ServerPerformanceData(this) ;
		this.playerData = new HashMap<String,PlayerData>() ;
		this.blockData = new HashMap<String,BlockData>() ;
		this.npeData = new HashMap<String,NpeData>();

		loadState() ;

		ObjectName name;
		try {
			String serverName = Bukkit.getServer().getName();
			name = new ObjectName("org.dkhenry.minejmx:type=ServerData,name="+serverName);
			if (mbs.isRegistered(name) ) {
				mbs.unregisterMBean(name) ;
			}
			mbs.registerMBean(serverData, name) ;

			name = new ObjectName("org.dkhenry.minejmx:type=ServerPerformanceData,name="+serverName) ;
			if (mbs.isRegistered(name) ) {
				mbs.unregisterMBean(name) ;
			}
			mbs.registerMBean(serverPerformanceData, name) ;
		} catch (MalformedObjectNameException e1) {
			//e1.printStackTrace();
		} catch (NullPointerException e1) {
			//e1.printStackTrace();
		} catch (InstanceAlreadyExistsException e) {
			//e.printStackTrace();
		} catch (MBeanRegistrationException e) {
			//e.printStackTrace();
		} catch (NotCompliantMBeanException e) {
			//e.printStackTrace();
		} catch (InstanceNotFoundException e) {
			//e.printStackTrace();
		}

		/* Register the Listeners */
		PluginManager pm = this.getServer().getPluginManager() ;
		// The Block Events		
		pm.registerEvents(blockListener, this) ; 		

		// Player Events
		pm.registerEvents(playerListener, this) ; 				

		// Entity Events
		pm.registerEvents(entityListener, this) ;
		
		// Server Events
		this.tickPoller = new ServerTickPoller(this) ;
		this.tickPoller.setInterval(40) ;
		this.tickPoller.registerWithScheduler(getServer().getScheduler()) ;

		log.info("The MineJMX Plugin has been enabled.") ;
	}

	public static String getSimpleClassName(Class cls) {
		String name = cls.getName().replace('$', '.');
		if(name.lastIndexOf('.') > 0) {
			name = name.substring(name.lastIndexOf('.') + 1);
		}
		return name ;
	}
}

