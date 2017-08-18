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

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.inbound.endpoint.persistence.ServiceReferenceHolder;
import org.wso2.carbon.registry.api.Registry;
import org.wso2.carbon.registry.api.RegistryException;
import org.wso2.carbon.registry.api.Resource;

import java.io.ObjectInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;

/**
 * Read/Write operations with registry
 */
public class DBEventRegistryHandler {
    private static final Log log = LogFactory.getLog(DBEventRegistryHandler.class.getName());
    private Resource resource;
    private Registry registry;
    private Object obj;

    public DBEventRegistryHandler() {
        try {
            registry = ServiceReferenceHolder.getInstance().getRegistry();
        } catch (RegistryException e) {
            log.error("Error while accessing the registry", e);
        }
    }

    public Object readFromRegistry(String resourcePath) {
        try {
            if (registry.resourceExists(resourcePath)) {
                resource = registry.get(resourcePath);
                byte[] content = (byte[]) resource.getContent();
                obj = toObject(content);
            } else {
                return getDefaultTimestamp();
            }
        } catch (RegistryException e) {
            log.error("Error while accessing the registry", e);
        }
        return obj;
    }

    private Object toObject(byte[] arrayDate) {
        ByteArrayInputStream bis = new ByteArrayInputStream(arrayDate);
        ObjectInputStream in = null;
        try {
            in = new ObjectInputStream(bis);
            return in.readObject();
        } catch (IOException e) {
            log.error("Error while reading the registry property", e);
        } catch (ClassNotFoundException e) {
            log.error("Unable to access readObject method ", e);
        } finally {
            try {
                in.close();
                bis.close();
            } catch (IOException e) {
                log.error("Error while closing the registry stream", e);
            }
        }
        return null;
    }

    private byte[] toByteArray(Object date) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream oos;
        try {
            oos = new ObjectOutputStream(bos);
            oos.writeObject(date);
            oos.flush();
            oos.close();
            bos.close();
        } catch (IOException e) {
            log.error("Error while reading the registry property", e);
        }
        return bos.toByteArray();
    }

    public void writeToRegistry(String resourceID, Object date) {
        try {
            resource = registry.newResource();
            resource.setContent(toByteArray(date));
            registry.put(resourceID, resource);
        } catch (RegistryException e) {
            log.error("Error while accessing the registry", e);
        }
    }

    /**
     *
     * @return current timestamp
     */
    private String getDefaultTimestamp() {
        Calendar cal = Calendar.getInstance();
        SimpleDateFormat sdf = new SimpleDateFormat(DBEventConstants.REGISTRY_TIME_FORMAT);
        cal.add(Calendar.MONTH, -1);
        return sdf.format(cal.getTime());
    }
}