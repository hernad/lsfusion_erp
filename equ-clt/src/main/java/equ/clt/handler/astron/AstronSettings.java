package equ.clt.handler.astron;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static org.apache.commons.lang3.StringUtils.trim;

public class AstronSettings implements Serializable {
    private Integer timeout;
    public String groupMachineries = null;

    public AstronSettings() {
    }

    public Integer getTimeout() {
        return timeout;
    }

    public void setTimeout(Integer timeout) {
        this.timeout = timeout;
    }

    public Map<Integer, Integer> getGroupMachineryMap() {
        Map<Integer, Integer> groupMachineryMap = new HashMap<>();
        if(groupMachineries != null) {
            for (String groupMachinery : groupMachineries.split(",")) {
                String[] entry = trim(groupMachinery).split("->");
                if (entry.length == 2) {
                    Integer key = parseInt(trim(entry[0]));
                    Integer value = parseInt(trim(entry[1]));
                    if (key != null && value != null)
                        groupMachineryMap.put(key, value);
                }
            }
        }
        return groupMachineryMap;
    }

    public void setGroupMachineries(String groupMachineries) {
        this.groupMachineries = groupMachineries;
    }

    private Integer parseInt(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return null;
        }
    }
}