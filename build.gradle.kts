plugins {
    base
    id("io.ia.sdk.modl") version "0.3.0"
    id("com.diffplug.spotless") version "7.2.0" apply false
}

allprojects {
    version = "0.1.0"
    group = "com.edgerton.arc"
}

ignitionModule {
    name.set("Arc")
    fileName.set("Arc")
    id.set("com.edgerton.arc")
    moduleVersion.set("${project.version}")
    moduleDescription.set("An Ignition module for project-aware AI context, chat, and workflows in Ignition Designer")
    requiredIgnitionVersion.set("8.3.0")
    requiredFrameworkVersion.set("8")
    freeModule.set(true)

    projectScopes.putAll(
        mapOf(
            ":common" to "GD",
            ":designer" to "D",
            ":gateway" to "G"
        )
    )

    hooks.putAll(
        mapOf(
            "com.edgerton.arc.designer.ArcDesignerHook" to "D",
            "com.edgerton.arc.gateway.ArcGatewayHook" to "G"
        )
    )

    skipModlSigning.set(true)
}

tasks.named("writeModuleXml") {
    doLast {
        val xmlFile = file("build/moduleContent/module.xml")
        if (xmlFile.exists()) {
            val content = xmlFile.readText()
            val vendorXml = "\t\t<vendorid>0</vendorid>\n\t\t<vendorname>Edgerton</vendorname>\n\t\t<vendorcontactinfo>https://github.com/TaylorEdgerton</vendorcontactinfo>\n"
            val patched = content.replace("</module>", "$vendorXml\t</module>")
            xmlFile.writeText(patched)
        }
    }
}
