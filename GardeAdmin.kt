package cg.garde.dns

import android.app.admin.DeviceAdminReceiver

/** Tant que cette app est "administrateur", Android refuse de la désinstaller. */
class GardeAdmin : DeviceAdminReceiver()
