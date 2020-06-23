package cz.muni.ics.oidc.server.adapters;

/**
 * Interface for getting data from Perun interfaces.
 * Used for fetching necessary data about users, services etc.
 *
 * @author Martin Kuba <makub@ics.muni.cz>
 * @author Dominik František Bučík <bucik@ics.muni.cz>
 * @author Peter Jancus <jancus@ics.muni.cz>
 */
public abstract class PerunAdapter implements PerunAdapterMethods {

	private PerunAdapterMethods adapterPrimary;
	private PerunAdapterMethods adapterFallback;
	private PerunAdapterMethodsRpc adapterRpc;
	private PerunAdapterMethodsLdap adapterLdap;

	private boolean callFallback;

	public PerunAdapterMethods getAdapterPrimary() {
		return adapterPrimary;
	}

	public void setAdapterPrimary(PerunAdapterMethods adapterPrimary) {
		this.adapterPrimary = adapterPrimary;
	}

	public PerunAdapterMethods getAdapterFallback() {
		return adapterFallback;
	}

	public void setAdapterFallback(PerunAdapterMethods adapterFallback) {
		this.adapterFallback = adapterFallback;
	}

	public PerunAdapterMethodsRpc getAdapterRpc() {
		return adapterRpc;
	}

	public void setAdapterRpc(PerunAdapterMethodsRpc adapterRpc) {
		this.adapterRpc = adapterRpc;
	}

	public PerunAdapterMethodsLdap getAdapterLdap() {
		return adapterLdap;
	}

	public void setAdapterLdap(PerunAdapterMethodsLdap adapterLdap) {
		this.adapterLdap = adapterLdap;
	}

	public boolean isCallFallback() {
		return callFallback;
	}

	public void setCallFallback(boolean callFallback) {
		this.callFallback = callFallback;
	}

}
