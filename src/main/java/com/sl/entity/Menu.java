package com.sl.entity;

public class Menu {
    private String idMenu;

    private String nameMenu;

    private String nameClass;

    private String idParent;

    private String nbrOrder;
    
    private String cdMenuType;
    
    private String nameIconPath;
    
    private String nameFavouritesIconPath;
    
    private String namePermission;
    
    private String flagLowest;
    
	public Menu() {
		super();
	}

	public Menu(String idMenu, String nameMenu, String nameClass, String idParent, String nbrOrder, String cdMenuType,
			String nameIconPath, String nameFavouritesIconPath, String namePermission, String flagLowest) {
		super();
		this.idMenu = idMenu;
		this.nameMenu = nameMenu;
		this.nameClass = nameClass;
		this.idParent = idParent;
		this.nbrOrder = nbrOrder;
		this.cdMenuType = cdMenuType;
		this.nameIconPath = nameIconPath;
		this.nameFavouritesIconPath = nameFavouritesIconPath;
		this.namePermission = namePermission;
		this.flagLowest = flagLowest;
	}

	public String getNamePermission() {
		return namePermission;
	}

	public void setNamePermission(String namePermission) {
		this.namePermission = namePermission;
	}

	public String getNameFavouritesIconPath() {
		return nameFavouritesIconPath;
	}

	public void setNameFavouritesIconPath(String nameFavouritesIconPath) {
		this.nameFavouritesIconPath = nameFavouritesIconPath;
	}

	public String getNameIconPath() {
		return nameIconPath;
	}

	public void setNameIconPath(String nameIconPath) {
		this.nameIconPath = nameIconPath;
	}

	public String getFlagLowest() {
		return flagLowest;
	}

	public void setFlagLowest(String flagLowest) {
		this.flagLowest = flagLowest;
	}

	public String getCdMenuType() {
		return cdMenuType;
	}

	public void setCdMenuType(String cdMenuType) {
		this.cdMenuType = cdMenuType;
	}

	public String getIdMenu() {
        return idMenu;
    }

    public void setIdMenu(String idMenu) {
        this.idMenu = idMenu == null ? null : idMenu.trim();
    }

    public String getNameMenu() {
        return nameMenu;
    }

    public void setNameMenu(String nameMenu) {
        this.nameMenu = nameMenu == null ? null : nameMenu.trim();
    }

    public String getNameClass() {
        return nameClass;
    }

    public void setNameClass(String nameClass) {
        this.nameClass = nameClass == null ? null : nameClass.trim();
    }

    public String getIdParent() {
        return idParent;
    }

    public void setIdParent(String idParent) {
        this.idParent = idParent == null ? null : idParent.trim();
    }

    public String getNbrOrder() {
        return nbrOrder;
    }

    public void setNbrOrder(String nbrOrder) {
        this.nbrOrder = nbrOrder == null ? null : nbrOrder.trim();
    }
}