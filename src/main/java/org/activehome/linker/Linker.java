package org.activehome.linker;

/*
 * #%L
 * Active Home :: Linker
 * $Id:$
 * $HeadURL:$
 * %%
 * Copyright (C) 2016 Active Home Project
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the 
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public 
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

import org.activehome.com.Request;
import org.activehome.com.RequestCallback;
import org.activehome.com.error.Error;
import org.activehome.com.error.ErrorType;
import org.activehome.context.data.ComponentProperties;
import org.activehome.context.data.UserInfo;
import org.activehome.context.helper.ModelHelper;
import org.activehome.service.RequestHandler;
import org.activehome.service.Service;
import org.kevoree.annotation.ComponentType;
import org.kevoree.annotation.KevoreeInject;
import org.kevoree.annotation.Param;
import org.kevoree.annotation.Start;
import org.kevoree.api.BootstrapService;
import org.kevoree.Package;
import org.kevoree.ContainerRoot;
import org.kevoree.ComponentInstance;
import org.kevoree.ContainerNode;
import org.kevoree.api.KevScriptService;
import org.kevoree.api.handler.UUIDModel;
import org.kevoree.factory.DefaultKevoreeFactory;
import org.kevoree.factory.KevoreeFactory;
import org.kevoree.log.Log;
import org.kevoree.pmodeling.api.ModelCloner;
import org.kevoree.TypeDefinition;

import java.util.HashMap;
import java.util.LinkedList;

/**
 * @author Jacky Bourgeois
 * @version %I%, %G%
 */
@ComponentType
public class Linker extends Service implements RequestHandler {

    private static String wsPort = "9050";
    @Param(defaultValue = "Automate the deployment of Active Home component on Kevoree.")
    private String description;
    @Param(defaultValue = "/active-home-linker")
    private String src;
    private ModelCloner cloner;

    @KevoreeInject
    private KevScriptService kevScriptService;
    @KevoreeInject
    private BootstrapService bootstrapService;

    private boolean first = true;

    @Start
    public void start() {
        super.start();
        KevoreeFactory kevFactory = new DefaultKevoreeFactory();
        cloner = kevFactory.createModelCloner();
    }


    @Override
    protected RequestHandler getRequestHandler(final Request request) {
        return this;
    }


    private Package getPackage(final ContainerRoot localModel,
                               final String packageName) {
        String[] packageArray = packageName.split("\\.");
        Package pack = localModel.findPackagesByID(packageArray[0]);
        for (int i = 1; i < packageArray.length; i++) {
            if (pack != null) {
                pack = pack.findPackagesByID(packageArray[i]);
            }
        }
        return pack;
    }

    private void scanPackage(Package pack) {
        if (pack != null) {
            for (Package subPack : pack.getPackages()) {
                scanPackage(subPack);
            }
        }
    }

    /**
     * Look for the a component type, retrieve properties (ports, attributes)
     * Then replace default values with those provided
     * <p>
     * Minimal requirement: getType() != null
     *
     * @param initialProp properties that will be insert in the results
     * @return a new instance of ComponentProperties containing all requirements
     */
    public ComponentProperties getComponentRequirements(final ComponentProperties initialProp,
                                                        final Package pack,
                                                        final TypeDefinition td) {
        ComponentProperties cp = new ComponentProperties(initialProp.getComponentName(), initialProp.getId(),
                initialProp.getAttributeMap(), initialProp.getExternalNodes());
        cp.getAttributeMap().putAll(initialProp.getAttributeMap());
        cp.getPortDestinationMap().putAll(initialProp.getPortDestinationMap());
        if (td != null) {
            if (td.getDictionaryType() != null) {
                td.getDictionaryType().getAttributes().stream().filter(da -> da.getName() != null).forEach(da -> {
                    if (!cp.getAttributeMap().containsKey(da.getName())) {
                        cp.getAttributeMap().put(da.getName(), da.getDefaultValue());
                    }
                });
            }
        } else {
            Log.warn("Linker (GetComponentRequirement): TypeDefinition null");
        }
        return cp;
    }

    private TypeDefinition extractTypeDef(final Package pack,
                                          final String type) {
        TypeDefinition td = null;
        for (TypeDefinition typeDef : pack.getTypeDefinitions()) {
            if (typeDef.getName() != null) {
                String typeWithoutVersion = type.split("/")[0];
                String className = typeWithoutVersion.substring(
                        typeWithoutVersion.lastIndexOf(".") + 1, typeWithoutVersion.length());
                if (typeDef.getName().compareTo(className) == 0) {
                    td = typeDef;
                }
            }
        }
        return td;
    }

    public void startComponent(final ComponentProperties initialCP,
                               final UserInfo userInfo,
                               final RequestCallback callback) {
        if (initialCP.getId() != null && initialCP.getComponentName() != null) {
            UUIDModel model = getModelService().getCurrentModel();
            ContainerRoot localModel = cloner.clone(model.getModel());
            ComponentInstance ci = getComponentInstance(localModel,
                    context.getNodeName(), initialCP.getId());
            if (ci != null) {
                // the component is running, nothing to do
                String warn = "Component '" + ci.getName() + "' already running.";
                Log.warn(warn);
                callback.success(true);
            } else {
                // prepare a new component to start
                String type = initialCP.getComponentName();
                String packageName = type.substring(0, type.lastIndexOf("."));
                Package pack = getPackage(localModel, packageName);
                TypeDefinition typeDef;
                final ComponentProperties cp;
                if (pack != null) {
                    typeDef = extractTypeDef(pack, type);
                    cp = getComponentRequirements(initialCP, pack, typeDef);
                } else {
                    typeDef = null;
                    cp = initialCP;
                }
                String script = generateScript(cp, localModel);
                logInfo("startComponent, generated script:");
                logInfo(script);
                getModelService().submitScript(script, applied -> {
                    if (applied) {
                        if (typeDef != null) {
                            callback.success(true);
                        } else {
                            UUIDModel newModel = getModelService().getCurrentModel();
                            ContainerRoot newLocalModel = cloner.clone(newModel.getModel());
                            updateBindings(newLocalModel, cp, callback);
                        }
                    } else {
                        sendError("Failed to start component " + initialCP.getId(), callback);
                    }
                });
            }
        } else {
            callback.error(new Error(ErrorType.NOT_FOUND,
                    "Missing type or name in component properties."));
        }
    }

    private ComponentInstance getComponentInstance(final ContainerRoot localModel,
                                                   final String nodeName,
                                                   final String compName) {
        if (localModel != null) {
            ContainerNode node = localModel.findNodesByID(nodeName);
            if (node != null) {
                return node.findComponentsByID(compName);
            }
        }
        return null;
    }

    public void stopComponentByType(final String[] typeArray,
                                    final RequestCallback callback) {
        StringBuilder script = new StringBuilder();
        StringBuilder listCompStr = new StringBuilder();
        UUIDModel model = getModelService().getCurrentModel();
        ContainerRoot localModel = cloner.clone(model.getModel());

        for (String type : typeArray) {
            LinkedList<String> runningComponent = ModelHelper.findAllRunning(type,
                    new String[]{context.getNodeName()}, localModel);
            for (String compName : runningComponent) {
                script.append("remove ").append(compName).append("\n");
                listCompStr.append(compName).append(",");
            }
        }

        Log.info("Linker (Stop by type): Ready to run script");
        Log.info(script.toString());

        if (!script.toString().equals("")) {
            getModelService().submitScript(script.toString(), applied -> {
                if (applied) {
                    callback.success(true);
                } else {
                    callback.error(new Error(ErrorType.STOP_ERROR, "Error while stopping components."));
                }
            });
        } else {
            callback.success(true);
        }

    }

    public void updateComponentAttribute(final String id,
                                         final String attr,
                                         final String val,
                                         final RequestCallback callback) {
        String script = "set " + context.getNodeName() + "." + id + "." + attr + " = \"" + val + "\"";
        getModelService().submitScript(script, applied -> {
            if (applied) {
                callback.success(true);
            } else {
                String errorMsg = "Waiting successful adaptation (updateComponentAttribute)";
                Log.error(errorMsg);
//                waitingSuccessfullAdaptUpdate.put(attr + "=" + val, callback);
            }
        });
    }

    public void stop(final String componentId,
                     final RequestCallback callback) {
        String script = "remove " + context.getInstanceName() + "." + componentId;
        getModelService().submitScript(script, applied -> {
            if (applied) {
                callback.success(true);
            } else {
                String errorMsg = "Waiting successful adaptation (stop)";
                Log.error(errorMsg);
                callback.error(new Error(ErrorType.STOP_ERROR, "Error while stopping component " + componentId + "."));
//                waitingSuccessfullAdaptStop.put(componentId, callback);
            }
        });
    }


    private String generateScript(final ComponentProperties cp,
                                  final ContainerRoot localModel) {
        StringBuilder script = new StringBuilder();
        // add node
        script.append(generateAddNodeScript(cp));

        // set attribute
        for (String key : cp.getAttributeMap().keySet()) {
            if (key.startsWith("binding")) {
                fillBindingMap(cp.getAttributeMap().get(key),
                        cp.getPortDestinationMap(), localModel);
            } else {
                script.append("set ").append(context.getNodeName()).append(".").append(cp.getId()).append(".")
                        .append(key).append(" = \"").append(cp.getAttributeMap().get(key)).append("\"\n");
            }
        }

        // set binding
        script.append(generateBindingScript(localModel, cp));

        return script.toString();
    }

    private String generateAddNodeScript(final ComponentProperties cp) {
        String nodeName = context.getNodeName();
        String[] typeSplit = cp.getComponentName().split("\\.");
        return "add " + nodeName + "." + cp.getId() + " : "
                + cp.getComponentName() + "\n";
    }

    private String generateBindingScript(final ContainerRoot localModel,
                                         final ComponentProperties cp) {
        String script = "";
        for (String port : cp.getPortDestinationMap().keySet()) {
            for (String dest : cp.getPortDestinationMap().get(port)) {
                String bScript = createBindScript(cp.getId(), port, dest, localModel);
                script += bScript;
            }
        }
        return script;
    }

    private void fillBindingMap(final String newBinding,
                                final HashMap<String, LinkedList<String>> mapToFill,
                                final ContainerRoot localModel) {
        for (String link : newBinding.split(",")) {
            String[] elems = link.split(">");
            if (!mapToFill.containsKey(elems[0])) {
                mapToFill.put(elems[0], new LinkedList<>());
            }
            String[] dest = elems[1].split("\\.");
            String[] nodes = new String[]{context.getNodeName()};
            for (String tkId : ModelHelper.findAllRunning(dest[0], nodes, localModel)) {
                mapToFill.get(elems[0]).add(tkId + "." + dest[1]);
            }
        }
    }

    /**
     * @param compId     the new component id
     * @param port       the port of the new component
     * @param dest       the destination port and component <nodeName.portName>
     * @param localModel
     * @return
     */
    String createBindScript(final String compId,
                            final String port,
                            final String dest,
                            final ContainerRoot localModel) {
        String script = "";
        String[] destParts = dest.split("\\.");
        //ComponentInstance ci = findComponent(destParts[0], localModel);
        LinkedList<String> chanToBeCreated = new LinkedList<>();
        String channelId = "chan_" + destParts[2] + "_" + context.getNodeName() + "_" + destParts[1];
        // if a channel hub does not exist yet
        if (!channelExists(channelId, localModel)) {
            boolean exist = false;
            // if we do not already plan to create this channel
            for (String chan : chanToBeCreated) if (chan.compareTo(channelId) == 0) exist = true;
            // then we need to create it
            if (!exist) {
                script += "add " + channelId + " : AsyncBroadcast\n";
//                script += "add " + channelId + " : RemoteWSChan\n";
//                script += "set " + channelId + ".host = \"" + NetworkHelper.getHostExternalAddr() + "\"\n" +
//                            "set " + channelId + ".port = \"" + wsPort + "\"\n" +
//                            "set " + channelId + ".uuid = \"" + UUID.randomUUID() + "\"\n" +
//                            "set " + channelId + ".path = \"/activehome\"\n";
                script += "bind " + dest + " " + channelId + "\n";
                chanToBeCreated.add(channelId);
            }
        }
        // finally we bind the link
        script += "bind " + context.getNodeName() + "." + compId + "." + port + " " + channelId + "\n";
        return script;
    }

    public boolean channelExists(final String id,
                                 final ContainerRoot localModel) {
        return localModel.findHubsByID(id) != null;
    }

    private void updateBindings(final ContainerRoot localModel,
                                final ComponentProperties initialCP,
                                final RequestCallback callback) {
        logInfo("updateBindings()");
        String type = initialCP.getComponentName();
        String typeNoVersion = type.split("/")[0];
        String packageName = typeNoVersion.substring(0, typeNoVersion.lastIndexOf("."));

        Package pack = getPackage(localModel, packageName);
        TypeDefinition typeDef = extractTypeDef(pack, type);
        if (pack == null) {
            logError("package " + packageName + " is null.");
        } else if (typeDef == null) {
            logError("type def " + type + " is null.");
        } else {
            final ComponentProperties cp = getComponentRequirements(initialCP, pack, typeDef);
            // set attribute
            cp.getAttributeMap().keySet().stream()
                    .filter(key -> key.startsWith("binding")).forEach(key -> {
                fillBindingMap(cp.getAttributeMap().get(key),
                        cp.getPortDestinationMap(), localModel);
            });
            String script = generateBindingScript(localModel, cp);
            logInfo(script);
            getModelService().submitScript(script, applied -> {
                if (applied) {
                    callback.success(true);
                } else {
                    sendError("Error while updating bindings for " + initialCP.getId(), callback);
                }
            });
        }
    }

    private void sendError(String msg, RequestCallback callback) {
        Thread.currentThread().setContextClassLoader(
                callback.getClass().getClassLoader());
        Error error = new Error(ErrorType.MODEL_UPDATE_FAILED, msg);
        callback.error(error);
    }

    public void findAllRunning(
            final String[] nodeNameArray,
            final ContainerRoot localModel) {
        if (localModel != null) {
            for (String nodeName : nodeNameArray) {
                ContainerNode node = localModel.findNodesByID(nodeName);
                if (node != null) {
                    node.getComponents().stream()
                            .forEach(ci -> {
                                System.out.println(ci.getName());
                            });
                }
            }
        }
    }

    public void pushScript(final String script,
                           final RequestCallback callback) {
        logInfo("pushScript:\n" + script);
        getModelService().submitScript(script, applied -> {
            if (applied) {
                callback.success(true);
            } else {
                String errorMsg = "Waiting successful adaptation (stop)";
                Log.error(errorMsg);
                callback.error(new Error(ErrorType.MODEL_UPDATE_FAILED, "Error while pushing script."));
            }
        });
    }
}



