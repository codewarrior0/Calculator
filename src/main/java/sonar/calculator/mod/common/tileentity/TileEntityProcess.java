package sonar.calculator.mod.common.tileentity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import io.netty.buffer.ByteBuf;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.item.EntityItem;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import sonar.core.SonarCore;
import sonar.core.api.SonarAPI;
import sonar.core.api.machines.IPausable;
import sonar.core.api.machines.IProcessMachine;
import sonar.core.api.upgrades.IUpgradableTile;
import sonar.core.common.tileentity.TileEntityEnergySidedInventory;
import sonar.core.helpers.FontHelper;
import sonar.core.helpers.NBTHelper.SyncType;
import sonar.core.helpers.SonarHelper;
import sonar.core.inventory.IAdditionalInventory;
import sonar.core.network.sync.IDirtyPart;
import sonar.core.network.sync.SyncTagType;
import sonar.core.network.sync.SyncTagType.INT;
import sonar.core.network.utils.IByteBufTile;
import sonar.core.upgrades.UpgradeInventory;
import sonar.core.utils.MachineSideConfig;

/** electric smelting tile entity */
public abstract class TileEntityProcess extends TileEntityEnergySidedInventory implements IUpgradableTile, IPausable, IAdditionalInventory, IProcessMachine, IByteBufTile {

	public float renderTicks;
	public double energyBuffer;

	public SyncTagType.BOOLEAN invertPaused = new SyncTagType.BOOLEAN(0);
	public SyncTagType.BOOLEAN paused = new SyncTagType.BOOLEAN(1);
	public SyncTagType.INT cookTime = (INT) new SyncTagType.INT(2);
	public UpgradeInventory upgrades = new UpgradeInventory(3, 16, "ENERGY", "SPEED", "TRANSFER").addMaxiumum("TRANSFER", 1);

	public boolean isActive = false;
	private ProcessState state = ProcessState.UNKNOWN;
	private int currentProcessTime = -1;

	public static int lowestSpeed = 4, lowestEnergy = 1000;

	// client
	public int currentSpeed;

	public TileEntityProcess() {
		syncList.addParts(paused, invertPaused, cookTime, upgrades);
	}

	public static enum ProcessState {
		TRUE, FALSE, UNKNOWN;

		public boolean canProcess() {
			return this == TRUE;
		}
	}

	public abstract boolean canProcess();

	public abstract void finishProcess();

	public void update() {
		super.update();
		if (isServer()) {
			if (upgrades.getUpgradesInstalled("TRANSFER") > 0) {
				transferItems();
			}
			if (!isPaused()) {
				boolean forceUpdate = false;
				if (getProcessState().canProcess()) {
					if (this.cookTime.getObject() >= this.getProcessTime()) {
						this.finishProcess();
						cookTime.setObject(0);
						this.energyBuffer = 0;
						forceUpdate = true;
					} else if (this.cookTime.getObject() > 0) {
						this.cookTime.increaseBy(1);
						modifyEnergy();
					} else if (cookTime.getObject() == 0) {
						this.cookTime.increaseBy(1);
						modifyEnergy();
						forceUpdate = true;
					}

				} else {
					renderTicks = 0;
					if (cookTime.getObject() != 0) {
						cookTime.setObject(0);
						this.energyBuffer = 0;
						SonarCore.sendPacketAround(this, 128, 2);
						forceUpdate = true;
					}
				}
				if (forceUpdate) {
					isActive = isActive();
					SonarCore.sendPacketAround(this, 128, 2);
					getWorld().addBlockEvent(pos, this.getBlockType(), 1, 1);
				}
			}
		} else {
			if (!isPaused()) {
				if (getProcessState().canProcess()) {
					renderTicks();
					cookTime.increaseBy(1);
				} else {
					if (cookTime.getObject() != 0) {
						renderTicks = 0;
						cookTime.setObject(0);
					}
				}
			}
		}

	}

	public ProcessState getProcessState() {
		return state = this.canProcess() ? ProcessState.TRUE : ProcessState.FALSE;
	}

	public void resetProcessState() {
		this.state = ProcessState.UNKNOWN;
		this.currentProcessTime = -1;
	}

	public void transferItems() {
		ArrayList<EnumFacing> outputs = sides.getSidesWithConfig(MachineSideConfig.OUTPUT);
		for (EnumFacing side : outputs) {
			SonarAPI.getItemHelper().transferItems(this, SonarHelper.getAdjacentTileEntity(this, side), side, side.getOpposite(), null);
		}
	}

	public void onFirstTick() {
		super.onFirstTick();
		if (!getWorld().isRemote) {
			isActive = this.isActive();
			SonarCore.sendPacketAround(this, 128, 2);
			getWorld().addBlockEvent(pos, this.getBlockType(), 1, 1);
		}
	}

	public void modifyEnergy() {
		energyBuffer += getEnergyUsage();
		int energyUsage = (int) Math.round(energyBuffer);
		if (energyBuffer - energyUsage < 0) {
			this.energyBuffer = 0;
		} else {
			energyBuffer -= energyUsage;
		}
		this.storage.modifyEnergyStored(-energyUsage);
	}

	public void renderTicks() {
		if (this instanceof TileEntityMachine.PrecisionChamber || this instanceof TileEntityMachine.ExtractionChamber) {
			this.renderTicks += (float) Math.max(1, upgrades.getUpgradesInstalled("SPEED")) / 50;
		} else {
			this.renderTicks += (float) Math.max(1, upgrades.getUpgradesInstalled("SPEED") * 8) / 1000;
		}
		if (this.renderTicks >= 2) {
			this.renderTicks = 0;
		}
	}

	public float getRenderPosition() {
		return renderTicks < 1 ? renderTicks : 1 - (renderTicks - 1);

	}

	private int roundNumber(double i) {
		return (int) (Math.ceil(i / 10) * 10);
	}

	public int requiredEnergy() {
		int speed = upgrades.getUpgradesInstalled("SPEED");
		int energy = upgrades.getUpgradesInstalled("ENERGY"); /* if (energy + speed == 0) { return 1000 * 5; } int i = 16 - (energy - speed); return roundNumber(((4 + ((i * i) * 2 + i)) * 2) * Math.max(1, (energy - speed))) * 5; */
		float i = (float) ((double) speed / 17) * getBaseEnergyUsage();
		float e = (float) ((double) energy / 17) * getBaseEnergyUsage();
		return (int) (getBaseEnergyUsage() + i - e);
	}

	public boolean receiveClientEvent(int action, int param) {
		if (action == 1) {
			markBlockForUpdate();
		}
		return true;
	}

	public void readData(NBTTagCompound nbt, SyncType type) {
		super.readData(nbt, type);
		if (type.isType(SyncType.DEFAULT_SYNC, SyncType.SAVE)) {
			if (type.isType(SyncType.DEFAULT_SYNC)) {
				this.currentSpeed = nbt.getInteger("speed");
			}
		}
	}

	public NBTTagCompound writeData(NBTTagCompound nbt, SyncType type) {
		if (isServer() && forceSync) {
			SonarCore.sendPacketAround(this, 128, 2);
		}
		super.writeData(nbt, type);
		if (type.isType(SyncType.DEFAULT_SYNC, SyncType.SAVE)) {
			if (type.isType(SyncType.DEFAULT_SYNC)) {
				nbt.setInteger("speed", this.getProcessTime());
			}
		}
		return nbt;
	}

	@Override
	public UpgradeInventory getUpgradeInventory() {
		return upgrades;
	}

	// IPausable
	@Override
	public boolean isActive() {
		if (getWorld().isRemote) {
			return isActive;
		}
		return !isPaused() && cookTime.getObject() > 0;
	}

	@Override
	public void onPause() {
		// paused.invert();
		markBlockForUpdate();
		this.getWorld().addBlockEvent(pos, blockType, 1, 1);
	}

	@Override
	public boolean isPaused() {
		// return invertPaused.getObject() ? paused.getObject() : !paused.getObject();
		return invertPaused.getObject();
		// return paused.getObject();
	}

	public boolean canStack(ItemStack current, ItemStack stack) {
		if (current == null) {
			return true;
		} else if (current.stackSize == current.getMaxStackSize()) {
			return false;
		}
		return true;
	}

	@SideOnly(Side.CLIENT)
	public List<String> getWailaInfo(List<String> currenttip, IBlockState state) {
		int speed = upgrades.getUpgradesInstalled("SPEED");
		int energy = upgrades.getUpgradesInstalled("ENERGY");
		if (speed != 0) {
			currenttip.add(FontHelper.translate("circuit.speed") + ": " + speed);
		}
		if (energy != 0) {
			currenttip.add(FontHelper.translate("circuit.energy") + ": " + energy);
		}
		return currenttip;
	}

	@Override
	public ItemStack[] getAdditionalStacks() {
		ArrayList<ItemStack> drops = upgrades.getDrops();
		if (drops == null || drops.isEmpty()) {
			return new ItemStack[] { null };
		}
		ItemStack[] toDrop = new ItemStack[drops.size()];
		int pos = 0;
		for (ItemStack drop : drops) {
			if (drop != null) {
				toDrop[pos] = drop;
			}
			pos++;
		}
		return toDrop;

	}

	public abstract int getBaseEnergyUsage();

	@Override
	public int getCurrentProcessTime() {
		return cookTime.getObject();
	}

	@Override
	public int getProcessTime() {
		if (this.currentProcessTime == -1) {
			int speed = upgrades.getUpgradesInstalled("SPEED");
			int energy = upgrades.getUpgradesInstalled("ENERGY");
			double i = (double) (((double) speed / 17) * getBaseProcessTime());
			if (speed == 16) {
				return currentProcessTime = 8;
			}
			return currentProcessTime = (int) Math.max(getBaseProcessTime() - i, lowestSpeed);
		}
		return currentProcessTime;
	}

	@Override
	public double getEnergyUsage() {
		return (double) requiredEnergy() / (double) getProcessTime();
	}

	@Override
	public void markChanged(IDirtyPart part) {
		super.markChanged(part);
		if (this.isServer() && (part == inv || part == upgrades)) {
			this.resetProcessState();
		}
	}

	@Override
	public void writePacket(ByteBuf buf, int id) {
		if (id == 0) {
		}
		if (id == 1) {
			invertPaused.invert();
			invertPaused.writeToBuf(buf);
		}
		if (id == 2) {
			invertPaused.writeToBuf(buf);
			paused.writeToBuf(buf);
			cookTime.writeToBuf(buf);
			buf.writeBoolean(isActive());
		}
	}

	@Override
	public void readPacket(ByteBuf buf, int id) {
		if (id == 0) {
			ItemStack[] upgrades = getAdditionalStacks();
			Random rand = new Random();
			for (ItemStack stack : upgrades) {
				if (stack != null) {
					float f = rand.nextFloat() * 0.8F + 0.1F;
					float f1 = rand.nextFloat() * 0.8F + 0.1F;
					float f2 = rand.nextFloat() * 0.8F + 0.1F;

					EntityItem dropStack = new EntityItem(getWorld(), pos.getX() + f, pos.getY() + f1, pos.getZ() + f2, stack);
					getWorld().spawnEntityInWorld(dropStack);
				}
			}
		}
		if (id == 1) {
			invertPaused.readFromBuf(buf);
			// onPause();
		}
		if (id == 2) {
			invertPaused.readFromBuf(buf);
			paused.readFromBuf(buf);
			cookTime.readFromBuf(buf);
			isActive = buf.readBoolean();
		}
	}
}
