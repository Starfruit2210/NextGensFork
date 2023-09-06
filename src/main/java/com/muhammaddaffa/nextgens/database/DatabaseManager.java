package com.muhammaddaffa.nextgens.database;

import com.muhammaddaffa.mdlib.utils.Config;
import com.muhammaddaffa.mdlib.utils.LocationSerializer;
import com.muhammaddaffa.mdlib.utils.Logger;
import com.muhammaddaffa.nextgens.NextGens;
import com.muhammaddaffa.nextgens.generators.ActiveGenerator;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.util.Consumer;

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

public class DatabaseManager {

    public static final String GENERATOR_TABLE = "nextgens_generator";
    public static final String USER_TABLE = "nextgens_user";

    private HikariDataSource dataSource;
    private boolean mysql;

    public void connect() {
        // get all variables we want
        FileConfiguration config = Config.getFileConfiguration("config.yml");
        String path = "plugins/NextGens/generators.db";
        // create the hikari config
        HikariConfig hikari = new HikariConfig();
        hikari.setConnectionTestQuery("SELECT 1");
        hikari.setPoolName("NextGens Database Pool");
        hikari.setConnectionTimeout(60000);
        hikari.setIdleTimeout(600000);
        hikari.setLeakDetectionThreshold(180000);
        hikari.addDataSourceProperty("characterEncoding", "utf8");
        hikari.addDataSourceProperty("useUnicode", true);

        if (config.getBoolean("mysql.enabled")) {
            this.mysql = true;
            String host = config.getString("mysql.host");
            int port = config.getInt("mysql.port");
            String database = config.getString("mysql.database");
            String user = config.getString("mysql.user");
            String password = config.getString("mysql.password");
            boolean useSSL = config.getBoolean("mysql.useSSL");
            Logger.info("Trying to connect to the MySQL database...");

            hikari.setDriverClassName("com.mysql.jdbc.Driver");
            hikari.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=%b",
                    host, port, database, useSSL));
            hikari.setUsername(user);
            hikari.setPassword(password);
            hikari.setMinimumIdle(5);
            hikari.setMaximumPoolSize(50);

            this.dataSource = new HikariDataSource(hikari);
            Logger.info("Successfully established connection with MySQL database!");

        } else {
            this.mysql = false;
            hikari.setConnectionTestQuery("SELECT 1");
            hikari.setDriverClassName("org.sqlite.JDBC");
            hikari.setJdbcUrl("jdbc:sqlite:" + path);
            hikari.setMaximumPoolSize(1);

            Logger.info("Trying to connect to the SQLite database...");
            // create the file if it's not exist
            try {
                File file = new File(path);
                if (!file.exists()) {
                    file.createNewFile();
                }
                this.dataSource = new HikariDataSource(hikari);
                Logger.info("Successfully established connection with SQLite database!");
            } catch (IOException ex) {
                Logger.severe("Failed to create the database file, stopping the server!");
                Bukkit.getPluginManager().disablePlugin(NextGens.getInstance());
                throw new RuntimeException(ex);
            }
        }
    }

    public void createGeneratorTable() {
        this.executeUpdate("CREATE TABLE IF NOT EXISTS " + GENERATOR_TABLE + " (" +
                "owner VARCHAR(255), " +
                "location TEXT UNIQUE, " +
                "generator_id TEXT, " +
                "timer DECIMAL(18,2), " +
                "is_corrupted INT" +
                ");");
    }

    public void createUserTable() {
        this.executeUpdate("CREATE TABLE IF NOT EXISTS " + USER_TABLE + " (" +
                "uuid VARCHAR(255) UNIQUE, " +
                "bonus INT" +
                ");");
    }

    public void deleteGenerator(ActiveGenerator active) {
        String query = "DELETE FROM " + GENERATOR_TABLE + " WHERE location=?;";
        this.buildStatement(query, statement -> {
            statement.setString(1, LocationSerializer.serialize(active.getLocation()));

            statement.executeUpdate();
        });
    }

    public void saveGenerator(ActiveGenerator active) {
        // if the world is null, skip it
        if (active.getLocation().getWorld() == null) {
            return;
        }

        String query = "REPLACE INTO " + GENERATOR_TABLE + " VALUES (?,?,?,?,?);";
        this.buildStatement(query, statement -> {
            statement.setString(1, active.getOwner().toString());
            statement.setString(2, LocationSerializer.serialize(active.getLocation()));
            statement.setString(3, active.getGenerator().id());
            statement.setDouble(4, active.getTimer());
            statement.setBoolean(5, active.isCorrupted());

            statement.executeUpdate();
        });
    }

    public void saveGenerator(Collection<ActiveGenerator> activeGenerators) {
        try (Connection connection = this.getConnection()) {
            String query = "REPLACE INTO " + GENERATOR_TABLE + " VALUES (?,?,?,?,?);";

            for (ActiveGenerator active : activeGenerators) {
                // if the world is null, skip it
                if (active.getLocation().getWorld() == null) {
                    continue;
                }

                try (PreparedStatement statement = connection.prepareStatement(query)) {
                    statement.setString(1, active.getOwner().toString());
                    statement.setString(2, LocationSerializer.serialize(active.getLocation()));
                    statement.setString(3, active.getGenerator().id());
                    statement.setDouble(4, active.getTimer());
                    statement.setBoolean(5, active.isCorrupted());

                    statement.executeUpdate();
                }
            }

            // send log message
            Logger.info("Successfully saved " + activeGenerators.size() + " active generators!");

        } catch (SQLException ex) {
            Logger.severe("Failed to save all generators!");
            ex.printStackTrace();
        }
    }

    public void executeUpdate(String statement) {
        this.executeUpdate(statement, error -> {
            Logger.severe("An error occurred while running statement: " + statement);
            error.printStackTrace();
        });
    }

    public void executeUpdate(String statement, Consumer<SQLException> onFailure) {
        try (Connection connection = this.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(statement)) {

            preparedStatement.executeUpdate();
        } catch (SQLException ex) {
            onFailure.accept(ex);
        }
    }

    public void executeQuery(String statement, QueryConsumer<ResultSet> callback) {
        this.executeQuery(statement, callback, error -> {
            Logger.severe("An error occurred while running query: " + statement);
            error.printStackTrace();
        });
    }

    public void executeQuery(String statement, QueryConsumer<ResultSet> callback, Consumer<SQLException> onFailure) {
        try (Connection connection = this.getConnection();
                PreparedStatement preparedStatement = connection.prepareStatement(statement);
                ResultSet result = preparedStatement.executeQuery()) {

            callback.accept(result);
        } catch (SQLException ex) {
            onFailure.accept(ex);
        }
    }

    public void buildStatement(String query, QueryConsumer<PreparedStatement> consumer) {
        this.buildStatement(query, consumer, error -> {
            Logger.severe("An error occurred while building statement: " + query);
            error.printStackTrace();
        });
    }

    public void buildStatement(String query, QueryConsumer<PreparedStatement> consumer, Consumer<SQLException> onFailure) {
        try (Connection connection = this.getConnection();
             PreparedStatement preparedStatement = connection.prepareStatement(query)) {

            consumer.accept(preparedStatement);
        } catch (SQLException ex) {
            onFailure.accept(ex);
        }
    }

    public Connection getConnection() throws SQLException {
        return this.dataSource.getConnection();
    }

    public void close() {
        this.dataSource.close();
    }

    public interface QueryConsumer<T> {

        void accept(T value) throws SQLException;

    }

}
