# Essential Loader Platforms

Unlike regular mods, essential-loader is subdivided in general not by Minecraft version but by which host mod loader it targets.
This is because Essential Loader does not by itself interact with Minecraft in any way.
But it does heavily interact with the host mod loader to dynamically load the mods it just downloaded / updated.

Currently there are five different platform:
- LaunchWrapper (Forge 1.8.9 and 1.12.2)
- Fabric (1.16+ with Fabric Loader)
- ModLauncher 8 (Forge 1.16.5)
- ModLauncher 9 (Forge 1.17 and above; also supports ModLauncher 10+ and NeoForge)
- FancyModLoader 10 (NeoForge 1.21.11 and above)

## LaunchWrapper

LaunchWrapper is what Forge 1.8.9 - 1.12.2 are based on.

### Background
Technically LaunchWrapper is independent from Forge (it is published by Mojang),
so stage0 is actually completely independent from Forge
and stage1 has separate code paths for pure LaunchWrapper vs LaunchWrapper+Forge.
In practise however, Forge is the only big user of LaunchWrapper and so stage2 assumes Forge.

Conceptually LaunchWrapper itself is actually quite simple (basically just three medium length files).
This makes it fairly easy to pick up but also relatively hard to master because there aren't any mechanisms for
dependency management or even any hard rules for what a Tweaker may or may not do, which doesn't mean that there aren't
rules, they are just all implicit in exactly how LaunchWrapper is implemented and are usually only discovered after
something blows up in some obscure configuration of mods.

### Entrypoint
LaunchWrapper's main entry point is a `ITweaker` class.

These are discovered and invoked by the `Launch` class in multiple rounds where each round can add extra tweaker classes
to be ran in the next round. The first round of Tweakers is discovered via command line arguments and in production
includes only Forge.
Forge will then look at the `TweakClass` property in the `MANIFEST.MF` file of jars in the mods folder during its early
loading process (`CoreModManager`) and queue these tweakers for the second round.
This is where we get to run.

In hindsight we could have also used Forge's CoreMod mechanism which gets to run (at least the initializer of coremods)
during the first round as Forge discovered them. But a Tweaker has worked well enough and changing this now would be a
huge undertaking.

Given this is all LaunchWrapper land and happens before Forge proper starts loading regular mods, actually chain-loading
production mods is almost trivial: We just add them to the classpath and Forge will discover them as usual.

One thing to note is that if essential-loader is to be used in a development environment, the entrypoint is somewhat
different because the tweaker is enabled via the command line. This is compensated for mostly by `DelayedStage0Tweaker`.

### Hacks
Despite its conceptual simplicity the stage2 loader for LaunchWrapper has actually grown to be arguably the most complex
piece of essential-loader.

This is mostly due to the amount of workarounds necessary to run modern versions of software (such as Mixin 0.8.x) on
these old Minecraft versions as well as due to the amount of badly written Tweakers/CoreMods in third-party code that
make everyone's lives more difficult.

#### Relaunch
The most powerful tool we have on LaunchWrapper is the "relaunch".
This which will create a second, mostly clean, mostly isolated environment within the existing LaunchWrapper environment
and re-launch Minecraft in there, this time with more recent versions of certain libraries and other modifications to
the inner LaunchWrapper that would otherwise not be possible to make.

Relaunching is always required on 1.8.9 because the default ASM library is too old for what Mixin 0.8 needs.
On 1.12.2 we could get away without relaunching as long as there is no other mod that pulls in an older version of one
of our libs (Kotlin being a frequent example), but for simplicity we'll simply always relaunch there as well.

#### Others
There are numerous other hacks used on this platform. For now, refer to the code.

## Fabric

The fabric platform is used wherever fabric-loader is used, regardless of Minecraft version.

### Background

With fabric-loader working across the entire range of supported Minecraft versions, the fabric variant of
essential-loader naturally does as well.
While this does mean that we're generally not completely stuck with old software like we are with unsupported Forge
versions, it does also mean that we can't snoop around in its internals as much because there can always be an update
that might break our hacks.

Unlike LaunchWrapper, fabric-loader was written to be a mod loader (take note ModLauncher!) and as such has excellent
support for functionality such as picking only the latest version of a mod, bundling mods inside other mods (Jar-in-Jar,
usually abbreviated as JiJ), first-party Mixin, etc.\
The only thing it is missing (unfortunately for our use case) is some way to chain-load mods / have custom code do mod discovery.
It always first discovers all mods before running any third-party code.

### Entrypoint

We use the builtin `prelaunch` entrypoint for essential-loader.

### Hacks

There are only three bigger hacks required on the fabric platform and they are all related to chain-loading the mod:
adding it to the class loader, registering it as a mod for other mods to see and extracting JiJ mods

#### Fake Mod

We make heavy use of fabric-loader internals to instantiate mod metadata for our dynamically loaded mods so they can
appear as normal mods in e.g. ModMenu.

Because this makes so heavy use of internals, it is particularly likely to break with fabric-loader updates, so we've
generally tried to keep optional. If it fails, the dynamically loaded mod will generally still function correctly, it
just won't show up in e.g. ModMenu.
This is notable because any declared entrypoints the mod has will also only be registered if this succeeds, so if
possible the mod should not rely on them (e.g. Essential uses a custom mixin for its init entrypoint instead of the
fabric builtin entrypoint).

#### JiJ

By the time we get to run, fabric-loader has already loaded the latest version of all mods and we need to take care of
any JiJ mods inside of our dynamically loaded mods ourselves.

If they have not yet been loaded or a newer version has already been loaded, this is trivial. However if an older
version of a mod we JiJ has already been loaded, we're in a bit of a predicament because we can't easily replace it with
the newer version.

If such a mod is found, Essential will dynamically generate a new "Essential Dependencies" mod in the mods folder before
prompting the user to restart the game. On next boot, fabric-loader will then see the jar we generated and load the
updated version of the JiJ mod from there instead of loading the old version like the previous time.

This does unfortunately mean the user may need to manually restart the game on each update of the JiJ mod but we were
unable to find a better solution without relying on fabric-loader internals too much (and this solution doesn't rely
on internal at all!).

## ModLauncher 8

ModLauncher 8 is used by Forge 1.16.5.

### Background

ModLauncher is Forge's successor to LaunchWrapper. It has much bigger ambitions and as such is much more complex.

Unfortunately for us, most of what it does is geared specifically to Forge and is of little use to us. So in the end 
it's usually just much more difficult to get it to do what we want. And consequently we more often need to resort to 
hacks and usually those are more complex too.

### Entrypoint

The early entrypoint which ModLauncher provides is the `TransformationService`.
Just like LaunchWrapper, they are discovered by Forge from jars in the mods folder,
this time via a file as used by Java's ServiceLoader rather than a manifest entry.

There are many sub-entrypoints in TransformationService, so to maintain maximum flexibility, stage1 actually has its own
`TransformationService` which we can update and we actually forward all the stage0 methods to stage1. And same thing
with stage1 to stage2 which we can auto-update.

One thing to note here is that ModLauncher (9+ in particular) does not allow two jars to share any packages. So anyone
packaging the stage0 loader **must** relocate it.
It also requires that each TransformationService have a unique name and because each must have a unique package, all
our transformation services (including the one in stage2) must be able to handle being instantiated multiple times and
must return a unique name for each (because TransformationService names must be unique too; we generate that one based
on the stage0 package).

Unlike LaunchWrapper, chain-loading our mods requires registering a ModLocator. Bit of a dance because it gets loaded
in a separate class loader that cannot directly communicate with the TransformationService but otherwise fairly straight
forward.

### Hacks

#### Upgrading third-party mods

Despite ModLauncher being a "Mod Loader" it fails to do the most basic things one could expect from a mod loader. In
particular it fails to provide a method to load the newer of two (language) mods.

The reason we need this is because on Forge, Kotlin is part of a mod called KotlinForForge and people frequently have
an old version of it installed.

We work our way around that by Unsafe-cloning the `LanguageLoadingProvider`, replacing it with a different
implementation (SortedLanguageLoadingProvider) that sorts the jars by implementation version and only picks the most
recent one where there are multiple jars for the same implementation name.

Note that Forge has a completely different mechanism for loading language mods than for loading regular mods and we only
deal with the language one right now because we don't yet need to upgrade regular ones.

#### Mod/Language load order

Modern Forge differentiates between regular Mods and Language Mods. Seems unnecessary but oh well..
Only direct issue we have with it is that classes in regular mods take priority over language mods, so even after we
upgrade KotlinForForge, if another mod also bundles the Kotlin stdlib, then we'll get their (usually outdated) version.

We fix this issue by messing around with the Lambda that's used to fetch the bytes for a given class name.
We have to do this before any classes are loaded though which is why we "register" (Unsafe) a
`EssentialLaunchPluginService` which happens to have a method that's invoked at the right time.

#### Upgrading Kotlin

KotlinForForge sometimes takes a while to update and also sometimes does backwards incompatible changes, so we might
break third-party mods if we were to just blindly auto-update it.
There's also Minecraft/Forge versions for which it no longer updates at all (basically anything that isn't Forge LTS).

Luckily with language mods still working fairly similar to LaunchWrapper (they're basically all just pushed into a
single URLClassLoader), it's fairly easy to upgrade Kotlin independently from KotlinForForge, we just push the Kotlin
stdlib into the class loader before KotlinForForge gets put in there (and we can do that in the same place as where we
already sort them for regular upgrades, so not even accessing it is difficult).

## ModLauncher 9

ModLauncher 9 is used by Forge 1.17+. It's main difference to ModLauncher 8 is that it heavily uses Java 9's modules
and that changes pretty much everything, leading to it receiving a separate platform.

ModLauncher 10 on the other hand is fairly similar to ModLauncher 9, so it is supported by the ModLauncher 9 variant
of essential-loader as well. There's an inner `modlauncher10` project for the one bit that is different; but it is
bundled inside the modlauncher9 stage2 jar, so from the outside the same stage2 jar will work for both.

### Background

See ModLauncher 8 section.

### Entrypoint

See ModLauncher 8 section.

The only reason we can't re-use stage0/1 from ML8 for ML9 is that the `TransformationService` interface now references
a `record` class, which we can't compile against with Java 8 required by ML8.

### Hacks

While the corresponding section in LaunchWrapper states that it has the most hacks, ModLauncher 9 isn't too far off,
and with ModLauncher generally being more complex, the hacks are too.

Additionally, since ModLauncher 9 now uses Java 9's modules, we often can't access (even public) members via reflection,
so not only do we need to mess with ModLauncher internals, we also need to rely on Unsafe a lot to even access those.

#### Chain loading mods

You'd think this would still be as easy as with ML8 or even easier now that language and regular mods are more uniform
but no, we still need to implement our own ModLocator but there's no longer any way for us to actually register it.
And so Forge will no longer call it for us, we now gotta do that ourselves and inject the results into Forge at the
correct moment in time (which we can't actually know for sure ahead of time because it depends on HashMap iteration
order; so we gotta try at multiple points).


#### What Minecraft version is this anyway?

Seems like a simple question but you won't get an answer from ModLauncher (even though it technically has the info).

We have to wait until the `FMLLoader` has run for it to make that info publicly available to us.
Only then can we instantiate the `ActualEssentialLoader` which will check for updates and download the initial version.
Until then, the `EssentialLoader` implementation in stage2 accessed by stage1 is actually just a dummy that gets a
hard-coded `1.17.1` as the Minecraft version and doesn't actually do anything other than providing access to the stage2
`TransformationService`.

#### Upgrading third-party mods

Not much has changed on a high level here from ModLauncher 9.
Despite being labeled a "Mod Loader", ModLauncher still fails to load the more recent of two versions.

Quite a lot has changed internally though:\
Instead of file system order, we now get HashMap iteration order, so effectively random on each run.\
Instead of simply loading one of the two, Java's module system will now throw an exception when two modules have overlapping packages.\
Luckily though if the same module is defined by two jars, it will just pick one of the two and not explode.

And on a positive note, "language mods" and "regular mods" are now both handled fairly similar, just in two different ModLauncher layers.
So once we fix one, we can fairly easily get the other one for free (at least in theory).

We work around these issues with our `SortedJarOrPathList` which is a `List` that sorts its elements
(`record JarOrPath`) by version.
Then we just replace the regular list for each layer with our list at the right time
(`configureLayerToBeSortedByVersion`) and we're able to (almost, see next hack) upgrade third-party mods!

#### Automatic module names

With above hack, all we need to do to upgrade a third-party mod is to inject a newer version with the same module name
and our hack will automatically choose the newer one. What could possibly go wrong?

In its infinite wisdom, Forge have decided that it's fine to derive the automatic module name (if one isn't specified
explicitly in the jar) from the file name.

So if you user downloads KFF from a different website or downloads it twice and the browser adds a little ` (1)` to the
name, it'll get a different automatic module name, we've therefore got two modules (the user installed one and our
updated one) with overlapping packages and ModLauncher goes nuclear again.

Introducing `SelfRenamingJarMetadata`. In an actually quite elegant way, this jar file metadata object will on-the-fly
when asked for its name go look through all other jar metadata registered on the same layer, check if any got
overlapping packages, and then steal their name.
\
And the way it's implemented, it would even work if another mod decided it needs to do the exact same thing. More
compatible than ModLauncher itself!

#### Upgrading Kotlin

KotlinForForge sometimes takes a while to update and also sometimes does backwards incompatible changes, so we might
break third-party mods if we were to just blindly auto-update it.
There's also Minecraft/Forge versions for which it no longer updates at all (basically anything that isn't Forge LTS).

Unfortunately we can't just throw the Kotlin stdlib directly into the classloader as we could on ModLauncher 9 because
that class loader isn't just a simple URLClassLoader anymore.

Shouldn't be much of a problem though because as of mid 2022 ModLauncher finally supports proper Jar-in-Jar
(they call it JarJar but I'll keep using the JiJ abbreviation I use everywhere),
so surely we can just upgrade the Kotlin stdlib itself and don't have to worry about KotlinForForge at all, right?

Oh, grave mistake! Assuming that just because Forge has finally implemented such a highly complex technology as JiJ a
mere eleven years after its initial release, that they've actually done so correctly...
\
It doesn't actually work properly for language mods. So KotlinForForge doesn't/can't yet use it.

The issue has been reported to them (MinecraftForge#8878) shortly after JiJ's release.
It is 2023 now and three major Forge versions later, the issue is still in the "yeah, we know. deal with it." stage.

So.. guess we'll deal with it. More hacks it is.

This time we automatically replace the entire KotlinForForge jar with an automatically generated one that is based on it
but has its bundled Kotlin upgraded from the one we bundle ourselves.

There's a few ugly details in there for when we actually want to include one of our versions. KotlinForForge still gets
updated, and we don't want to downgrade Kotlin stdlibs and there's no nice way to know the version that's in the loaded
KotlinForForge jar because it's just been exploded into there, all metadata lost. But we can get the coroutines version
from some random file it has and the stdlib version by loading the stdlib `KotlinVersion` class in a temporary class
loader, and then just take a good guess at the serialization version, and it all just about works out good enough.

## FancyModLoader 10

FancyModLoader 10 is used by NeoForge 1.21.11.
It is superficially quite similar to ModLauncher in that it provides many of the same NeoForge-specific service loader
interfaces, but drops all the ModLauncher-specific ones.

### Entrypoint

We implement an `IModFileCandidateLocator` as our entrypoint.

### Hacks

Presently no hacks are required on this platform.
The downloaded Essential jar is simply added to the discovery pipeline via its regular API.
