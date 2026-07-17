package gg.lakehouse.cctv;

import com.mojang.logging.LogUtils;
import dan200.computercraft.api.ComputerCraftAPI;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(CCTV.MOD_ID)
public final class CCTV {
    public static final String MOD_ID = "cctv";
    public static final Logger LOGGER = LogUtils.getLogger();

    public CCTV() {
        LOGGER.info("CC:TV starting alongside {}", ComputerCraftAPI.MOD_ID);
    }
}
