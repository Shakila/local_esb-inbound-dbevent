/*
* Copyright (c) 2017, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
*
* Licensed under the Apache License, Version 2.0 (the "License");
* you may not use this file except in compliance with the License.
* You may obtain a copy of the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.wso2.carbon.inbound.poll.dbeventlistener;

import org.apache.axiom.om.OMAbstractFactory;
import org.apache.axiom.om.OMElement;
import org.apache.axiom.om.OMFactory;
import org.apache.axiom.om.util.UUIDGenerator;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.synapse.MessageContext;
import org.apache.synapse.core.SynapseEnvironment;
import org.apache.synapse.mediators.base.SequenceMediator;
import org.wso2.carbon.base.MultitenantConstants;
import org.wso2.carbon.context.PrivilegedCarbonContext;
import org.wso2.carbon.inbound.endpoint.protocol.generic.GenericPollingConsumer;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Properties;

import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.sql.DriverManager;
import java.sql.Types;

public class DBEventPollingConsumer extends GenericPollingConsumer {

    private static final Log log = LogFactory.getLog(DBEventPollingConsumer.class);

    private String driverClass;
    private String dbURL;
    private String dbUsername;
    private String dbPassword;
    private String listeningColumnName;
    private String listeningCriteria;
    private String dbScript = null;
    private Connection connection = null;
    private Statement statement = null;
    private MessageContext msgCtx;
    private String deleteQuery = null;
    private String updateQuery = null;
    private String currentTimestamp = null;
    private String registryPath = null;

    /**
     * @param properties
     * @param name
     * @param synapseEnvironment
     * @param scanInterval
     * @param injectingSeq
     * @param onErrorSeq
     * @param coordination
     * @param sequential
     */
    public DBEventPollingConsumer(Properties properties, String name, SynapseEnvironment synapseEnvironment,
            long scanInterval, String injectingSeq, String onErrorSeq, boolean coordination, boolean sequential) {
        super(properties, name, synapseEnvironment, scanInterval, injectingSeq, onErrorSeq, coordination, sequential);
        driverClass = properties.getProperty(DBEventConstants.DB_DRIVER);
        dbURL = properties.getProperty(DBEventConstants.DB_URL);
        dbUsername = properties.getProperty(DBEventConstants.DB_USERNAME);
        dbPassword = properties.getProperty(DBEventConstants.DB_PASSWORD);
        listeningCriteria = properties.getProperty(DBEventConstants.DB_LISTENING_CRITERIA);
        listeningColumnName = properties.getProperty(DBEventConstants.DB_LISTENING_COLUMN_NAME);
        registryPath = properties.getProperty(DBEventConstants.REGISTRY_PATH);
        if(StringUtils.isEmpty(registryPath)) {
            registryPath = name;
        }
    }

    /**
     * Inject the message to the sequence
     *
     * @param object
     * @return status
     */
    public boolean inject(OMElement object) {
        DBEventRegistryHandler dbEventListnerRegistryHandler = new DBEventRegistryHandler();
        msgCtx = createMessageContext();
        if (injectingSeq == null || injectingSeq.equals("")) {
            log.error("Sequence name not specified. Sequence : " + injectingSeq);
            return false;
        }
        SequenceMediator seq = (SequenceMediator) synapseEnvironment.getSynapseConfiguration()
                .getSequence(injectingSeq);
        String query = null;
        try {
            msgCtx.getEnvelope().getBody().addChild(object);
            if (seq != null) {
                seq.setErrorHandler(onErrorSeq);
                if (log.isDebugEnabled()) {
                    log.info("injecting message to sequence : " + injectingSeq);
                }
                synapseEnvironment.injectInbound(msgCtx, seq, sequential);
            } else {
                log.error("Sequence: " + injectingSeq + " not found.");
            }
            if (isRollback(msgCtx)) {
                return false;
            } else {
                if (listeningCriteria.equals(DBEventConstants.DB_LISTENING_BY_TIMESTAMP)) {
                    dbEventListnerRegistryHandler.writeToRegistry(registryPath, currentTimestamp);
                }
                if (StringUtils.isNotEmpty(deleteQuery)) {
                    statement = connection.prepareStatement(deleteQuery);
                    query = deleteQuery;
                    statement.execute(deleteQuery);
                } else if (StringUtils.isNotEmpty(updateQuery)) {
                    statement = connection.prepareStatement(updateQuery);
                    query = updateQuery;
                    statement.execute(updateQuery);
                }
            }
        } catch (SQLException e) {
            log.error("Error while capturing the change data " + query, e);
        }
        return true;
    }

    private MessageContext createMessageContext() {
        MessageContext msgCtx = synapseEnvironment.createMessageContext();
        org.apache.axis2.context.MessageContext axis2MsgCtx = ((org.apache.synapse.core.axis2.Axis2MessageContext) msgCtx)
                .getAxis2MessageContext();
        axis2MsgCtx.setServerSide(true);
        axis2MsgCtx.setMessageID(UUIDGenerator.getUUID());
        msgCtx.setProperty(org.apache.axis2.context.MessageContext.CLIENT_API_NON_BLOCKING, true);
        PrivilegedCarbonContext carbonContext = PrivilegedCarbonContext.getThreadLocalCarbonContext();
        axis2MsgCtx.setProperty(MultitenantConstants.TENANT_DOMAIN, carbonContext.getTenantDomain());
        return msgCtx;
    }

    /**
     * Execute the query to retrieve the records, create each record as OMElement and inject to the sequence
     */
    private void executeDBQuery() {
        String tableName = properties.getProperty(DBEventConstants.DB_TABLE);
        DBEventRegistryHandler dbEventListnerRegistryHandler = new DBEventRegistryHandler();
        String lastUpdatedTimestampFromRegistry = null;
        if (listeningCriteria.equals(DBEventConstants.DB_LISTENING_BY_TIMESTAMP)) {
            lastUpdatedTimestampFromRegistry = dbEventListnerRegistryHandler.readFromRegistry(registryPath).toString();
        }
        dbScript = buildQuery(tableName, listeningCriteria,
                listeningColumnName, lastUpdatedTimestampFromRegistry);
        try {
            statement = connection.prepareStatement(dbScript);
            ResultSet rs = statement.executeQuery(dbScript);
            ResultSetMetaData metaData = rs.getMetaData();
            Boolean isRecordExist = false;
            DatabaseMetaData meta = connection.getMetaData();
            ResultSet primaryKeysResultSet;
            List<String> primaryKeys = new ArrayList<>();
            if (listeningCriteria.equals(DBEventConstants.DB_DELETE_AFTER_POLL) || listeningCriteria
                    .equals(DBEventConstants.DB_LISTENING_BY_BOOLEAN)) {
                primaryKeysResultSet = meta.getPrimaryKeys(null, null, tableName);
                while (primaryKeysResultSet.next()) {
                    primaryKeys.add(primaryKeysResultSet.getString("COLUMN_NAME"));
                }
            }
            while (rs.next()) {
                if (listeningCriteria.equals(DBEventConstants.DB_DELETE_AFTER_POLL)) {
                    deleteQuery = "DELETE FROM " + tableName + " WHERE ";
                } else if (listeningCriteria.equals(DBEventConstants.DB_LISTENING_BY_BOOLEAN)) {
                    updateQuery = "UPDATE " + tableName + " SET " + listeningColumnName + "='false'" + " WHERE ";
                }
                OMFactory factory = OMAbstractFactory.getOMFactory();
                OMElement result = factory.createOMElement("Record", null);
                int count = metaData.getColumnCount();
                for (int i = 1; i <= count; i++) {
                    String columnName = metaData.getColumnName(i);
                    int type = metaData.getColumnType(i);
                    String columnValue = "";
                    if (type == Types.VARCHAR || type == Types.CHAR) {
                        columnValue = rs.getString(columnName);
                    } else if (type == Types.INTEGER) {
                        columnValue = rs.getInt(columnName) + "";
                    } else if (type == Types.LONGVARCHAR) {
                        columnValue = rs.getLong(columnName) + "";
                    }
                    if(StringUtils.isNotEmpty(deleteQuery) && primaryKeys.contains(columnName)) {
                        deleteQuery += columnName + "='" + columnValue + "' AND ";
                    } else if(StringUtils.isNotEmpty(updateQuery) && primaryKeys.contains(columnName)) {
                        updateQuery += columnName + "='" + columnValue + "' AND ";
                    }
                    OMElement messageElement = factory.createOMElement(columnName, null);
                    messageElement.setText(columnValue);
                    result.addChild(messageElement);
                    isRecordExist = true;
                }
                if (isRecordExist) {
                    if (StringUtils.isNotEmpty(deleteQuery) && deleteQuery.lastIndexOf(" AND ") > 0) {
                        deleteQuery = deleteQuery.substring(0, deleteQuery.lastIndexOf(" AND "));
                    } else if (StringUtils.isNotEmpty(updateQuery) && updateQuery.lastIndexOf(" AND ") > 0) {
                        updateQuery = updateQuery.substring(0, updateQuery.lastIndexOf(" AND "));
                    }
                    this.inject(result);
                }
            }
        } catch (SQLException e) {
            log.error("Error while capturing the change data " + dbScript, e);
        }
    }

    /**
     *
     * @param tableName
     * @param listeningCriteria
     * @param listeningColumnName
     * @param lastUpdatedTimestampFromRegistry
     * @return query
     */
    private String buildQuery(String tableName, String listeningCriteria, String listeningColumnName,
            String lastUpdatedTimestampFromRegistry) {
        if (listeningCriteria.equals(DBEventConstants.DB_LISTENING_BY_TIMESTAMP)) {
            return "SELECT * FROM " + tableName + " WHERE " + listeningColumnName + " > '"
                    + lastUpdatedTimestampFromRegistry + "' ORDER BY " + listeningColumnName;
        } else if (listeningCriteria.equals(DBEventConstants.DB_LISTENING_BY_BOOLEAN)){
            return "SELECT * FROM " + tableName + " WHERE " + listeningColumnName + "='true'";
        } else {
            return "SELECT * FROM " + tableName;
        }
    }

    @Override
    public void destroy() {
        try {
            if (statement != null) {
                statement.close();
            }
        } catch (SQLException sqle) {
            log.error("Error while closing the SQL statement.", sqle);
        }
        try {
            if (connection != null) {
                connection.close();
            }
        } catch (SQLException sqle) {
            log.error("Error while destroying the connection to '" + dbURL + "'", sqle);
        }
    }

    /**
     * Create the database connection
     */
    private void createConnection() {
        try {
            Class.forName(driverClass);
            connection = DriverManager.getConnection(dbURL, dbUsername, dbPassword);
        } catch (ClassNotFoundException e) {
            log.error("Unable to find the driver class " + driverClass, e);
        } catch (SQLException e) {
            log.error("Error while creating the connection to " + dbURL, e);
        }
    }

    @Override
    public Object poll() {
        try {
            if (connection == null || connection.isClosed()) {
                createConnection();
            }
            currentTimestamp = getCurrentDateTime();
            executeDBQuery();
        } catch (SQLException e) {
            log.error("Error while checking the connection.", e);
        }
        return null;
    }

    /**
     * @return current timestamp
     */
    private String getCurrentDateTime() {
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat(DBEventConstants.REGISTRY_TIME_FORMAT);
        return sdf.format(cal.getTime());
    }

    /**
     * Check whether the message should be rolled back or not.
     */
    private boolean isRollback(MessageContext msgCtx) {
        Object rollbackProp = msgCtx.getProperty(DBEventConstants.IS_ROLLBACK);
        if (rollbackProp != null) {
            if ((rollbackProp instanceof Boolean && ((Boolean) rollbackProp))
                    || (rollbackProp instanceof String && Boolean.valueOf((String) rollbackProp))) {
                return true;
            }
            return false;
        }
        return false;
    }
}