(ns skein.mixin.codegen
  "ASM bytecode generation for mixin declarations (build-time only).

  Consumes the resolved IR (see skein.mixin.resolve) and emits, per
  declaration, a class with @Mixin/@Inject/@ModifyArg/@At annotations
  whose method bodies do one thing: call
  skein.runtime.SkeinHooks.call(handler, args...) — an IFn invocation
  through the handler var, so the logic stays hot-reloadable while the
  injection points are baked in.

  Generating the annotations as raw bytecode (instead of compiling
  Clojure or Java sources) sidesteps the Clojure compiler's limits on
  nested annotations entirely. No refmap and no remapping exist in the
  MC 26.x toolchain, so the emitted class is final — identical in dev
  and production.

  Requires org.ow2.asm on the classpath; the Fabric dev classpath
  always has it (the loader itself depends on ASM). Never require this
  namespace from mod code."
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (org.objectweb.asm AnnotationVisitor ClassWriter MethodVisitor Opcodes Type)))

(def ^:private hooks-internal "skein/runtime/SkeinHooks")
(def ^:private hooks-call-desc "(Ljava/lang/String;[Ljava/lang/Object;)Ljava/lang/Object;")
(def ^:private ci-desc "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfo;")
(def ^:private cir-desc "Lorg/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable;")
(def ^:private cir-internal "org/spongepowered/asm/mixin/injection/callback/CallbackInfoReturnable")

(def ^:private ann-mixin "Lorg/spongepowered/asm/mixin/Mixin;")
(def ^:private ann-inject "Lorg/spongepowered/asm/mixin/injection/Inject;")
(def ^:private ann-modify-arg "Lorg/spongepowered/asm/mixin/injection/ModifyArg;")
(def ^:private ann-at "Lorg/spongepowered/asm/mixin/injection/At;")

;;; Primitive boxing (hook arguments travel as Object[])

(def ^:private boxes
  {"Z" ["java/lang/Boolean" "booleanValue"]
   "B" ["java/lang/Byte" "byteValue"]
   "C" ["java/lang/Character" "charValue"]
   "S" ["java/lang/Short" "shortValue"]
   "I" ["java/lang/Integer" "intValue"]
   "J" ["java/lang/Long" "longValue"]
   "F" ["java/lang/Float" "floatValue"]
   "D" ["java/lang/Double" "doubleValue"]})

(defn- emit-int [^MethodVisitor mv n]
  (cond
    (<= 0 n 5) (.visitInsn mv (+ Opcodes/ICONST_0 n))
    (<= n Byte/MAX_VALUE) (.visitIntInsn mv Opcodes/BIPUSH n)
    :else (.visitIntInsn mv Opcodes/SIPUSH n)))

(defn- load-boxed
  "Loads the local at slot (of the given descriptor) and leaves it on
  the stack as an Object."
  [^MethodVisitor mv ^String desc slot]
  (.visitVarInsn mv (.getOpcode (Type/getType desc) Opcodes/ILOAD) slot)
  (when-let [[owner] (boxes desc)]
    (.visitMethodInsn mv Opcodes/INVOKESTATIC owner "valueOf"
                      (str "(" desc ")L" owner ";") false)))

(defn- unbox-or-cast
  "Turns the Object on the stack into a value of the given descriptor."
  [^MethodVisitor mv ^String desc]
  (if-let [[owner unbox-method] (boxes desc)]
    (do (.visitTypeInsn mv Opcodes/CHECKCAST owner)
        (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL owner unbox-method (str "()" desc) false))
    (.visitTypeInsn mv Opcodes/CHECKCAST (.getInternalName (Type/getType desc)))))

(defn- emit-hook-call
  "LDC handler-name, pack the loaders' values into an Object[], call
  SkeinHooks.call — leaves the handler's result (Object) on the stack.
  Each loader is a thunk emitting code that pushes exactly one Object."
  [^MethodVisitor mv handler loaders]
  (.visitLdcInsn mv handler)
  (emit-int mv (count loaders))
  (.visitTypeInsn mv Opcodes/ANEWARRAY "java/lang/Object")
  (doseq [[i loader] (map-indexed vector loaders)]
    (.visitInsn mv Opcodes/DUP)
    (emit-int mv i)
    (loader)
    (.visitInsn mv Opcodes/AASTORE))
  (.visitMethodInsn mv Opcodes/INVOKESTATIC hooks-internal "call" hooks-call-desc false))

;;; Annotations
;;; Retention differs across Sponge's annotations and each must land in
;;; the matching bytecode table or the processor will not see it:
;;; @Mixin is CLASS retention (invisible table), the injector
;;; annotations (@Inject, @ModifyArg — and @At nested inside them) are
;;; RUNTIME retention (visible table).

(defn- visit-at [^AnnotationVisitor at-visitor {:keys [value target ordinal]}]
  (.visit at-visitor "value" value)
  (when target (.visit at-visitor "target" target))
  (when ordinal (.visit at-visitor "ordinal" (int ordinal)))
  (.visitEnd at-visitor))

(defn- annotate-mixin-class [^ClassWriter cw target-internal]
  (let [av (.visitAnnotation cw ann-mixin false)
        arr (.visitArray av "value")]
    (.visit arr nil (Type/getObjectType target-internal))
    (.visitEnd arr)
    (.visitEnd av)))

(defn- annotate-inject [^MethodVisitor mv {:keys [name desc]} at cancellable?]
  (let [av (.visitAnnotation mv ann-inject true)
        methods (.visitArray av "method")]
    (.visit methods nil (str name desc))
    (.visitEnd methods)
    (let [ats (.visitArray av "at")]
      (visit-at (.visitAnnotation ats nil ann-at) at)
      (.visitEnd ats))
    (when cancellable? (.visit av "cancellable" true))
    (.visitEnd av)))

(defn- annotate-modify-arg [^MethodVisitor mv {:keys [name desc]} at index]
  (let [av (.visitAnnotation mv ann-modify-arg true)
        methods (.visitArray av "method")]
    (.visit methods nil (str name desc))
    (.visitEnd methods)
    (visit-at (.visitAnnotation av "at" ann-at) at)
    (.visit av "index" (int index))
    (.visitEnd av)))

;;; Injector methods
;;; Locals layout: [this?] param... [ci/cir]; longs and doubles take two
;;; slots. Generated names are skein$<targetMethod>$<n> — private and
;;; never referenced, so collisions are impossible by construction.

(defn- param-slots
  "[[desc slot] ...] for the target method's parameters."
  [{:keys [static param-descs]}]
  (loop [descs param-descs
         slot (if static 0 1)
         out []]
    (if-let [[d & more] (seq descs)]
      (recur more
             (+ slot (.getSize (Type/getType ^String d)))
             (conj out [d slot]))
      out)))

(defn- method-access ^long [{:keys [static]}]
  (if static
    (bit-or Opcodes/ACC_PRIVATE Opcodes/ACC_STATIC)
    Opcodes/ACC_PRIVATE))

(defn- this-loader [^MethodVisitor mv {:keys [static]}]
  (when-not static
    [(fn [] (.visitVarInsn mv Opcodes/ALOAD 0))]))

(defmulti ^:private gen-injector (fn [_cw _gen-name inject] (:type inject)))

;; :inject — private void skein$m$n(<target params>, CallbackInfo[Returnable])
;; body: SkeinHooks.call(handler, this?, params..., ci); return.
(defmethod gen-injector :inject
  [^ClassWriter cw gen-name {:keys [method at cancellable handler]}]
  (let [void-target? (= "V" (:return-desc method))
        callback-desc (if void-target? ci-desc cir-desc)
        params (param-slots method)
        ci-slot (if (seq params)
                  (let [[d s] (peek params)] (+ s (.getSize (Type/getType ^String d))))
                  (if (:static method) 0 1))
        mv (.visitMethod cw (method-access method) gen-name
                         (str "(" (apply str (:param-descs method)) callback-desc ")V")
                         nil nil)]
    (annotate-inject mv method at cancellable)
    (.visitCode mv)
    (emit-hook-call mv handler
                    (concat (this-loader mv method)
                            (map (fn [[d s]] #(load-boxed mv d s)) params)
                            [#(.visitVarInsn mv Opcodes/ALOAD ci-slot)]))
    (.visitInsn mv Opcodes/POP)
    (.visitInsn mv Opcodes/RETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

;; :modify-arg — private ArgType skein$m$n(ArgType arg)
;; body: return (ArgType) SkeinHooks.call(handler, arg);
(defmethod gen-injector :modify-arg
  [^ClassWriter cw gen-name {:keys [method at index arg-desc handler]}]
  (let [arg-slot (if (:static method) 0 1)
        mv (.visitMethod cw (method-access method) gen-name
                         (str "(" arg-desc ")" arg-desc)
                         nil nil)]
    (annotate-modify-arg mv method at index)
    (.visitCode mv)
    (emit-hook-call mv handler [#(load-boxed mv arg-desc arg-slot)])
    (unbox-or-cast mv arg-desc)
    (.visitInsn mv (.getOpcode (Type/getType ^String arg-desc) Opcodes/IRETURN))
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

;; :modify-return — an @Inject at every RETURN with a cancellable
;; CallbackInfoReturnable; body:
;;   cir.setReturnValue(SkeinHooks.call(handler, this?, cir.getReturnValue()))
;; getReturnValue/setReturnValue work on Object, so primitives box and
;; unbox inside Sponge's callback — no descriptor juggling here.
(defmethod gen-injector :modify-return
  [^ClassWriter cw gen-name {:keys [method handler]}]
  (let [params (param-slots method)
        cir-slot (if (seq params)
                   (let [[d s] (peek params)] (+ s (.getSize (Type/getType ^String d))))
                   (if (:static method) 0 1))
        mv (.visitMethod cw (method-access method) gen-name
                         (str "(" (apply str (:param-descs method)) cir-desc ")V")
                         nil nil)]
    (annotate-inject mv method {:value "RETURN"} true)
    (.visitCode mv)
    (.visitVarInsn mv Opcodes/ALOAD cir-slot)
    (emit-hook-call mv handler
                    (concat (this-loader mv method)
                            [#(do (.visitVarInsn mv Opcodes/ALOAD cir-slot)
                                  (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL cir-internal
                                                    "getReturnValue" "()Ljava/lang/Object;" false))]))
    (.visitMethodInsn mv Opcodes/INVOKEVIRTUAL cir-internal
                      "setReturnValue" "(Ljava/lang/Object;)V" false)
    (.visitInsn mv Opcodes/RETURN)
    (.visitMaxs mv 0 0)
    (.visitEnd mv)))

;;; Class generation

(defn generate-class
  "Bytecode of the mixin class for one resolved declaration."
  ^bytes [{:keys [target class injects]}]
  (let [cw (ClassWriter. ClassWriter/COMPUTE_MAXS)
        internal (str/replace class "." "/")]
    ;; V17 keeps the class loadable everywhere the game runs; the mixin
    ;; config's compatibilityLevel only caps, never floors, this.
    (.visit cw Opcodes/V17
            (bit-or Opcodes/ACC_SUPER Opcodes/ACC_ABSTRACT)
            internal nil "java/lang/Object" nil)
    (annotate-mixin-class cw (str/replace target "." "/"))
    ;; The no-arg constructor javac would emit for a handwritten stub;
    ;; Sponge never merges constructors, it just wants a valid class.
    (let [mv (.visitMethod cw Opcodes/ACC_PROTECTED "<init>" "()V" nil nil)]
      (.visitCode mv)
      (.visitVarInsn mv Opcodes/ALOAD 0)
      (.visitMethodInsn mv Opcodes/INVOKESPECIAL "java/lang/Object" "<init>" "()V" false)
      (.visitInsn mv Opcodes/RETURN)
      (.visitMaxs mv 0 0)
      (.visitEnd mv))
    (doseq [[i inject] (map-indexed vector injects)]
      (gen-injector cw (str "skein$" (get-in inject [:method :name]) "$" i) inject))
    (.visitEnd cw)
    (.toByteArray cw)))

(defn write-classes!
  "Generates and writes every declaration's class under out-dir
  (package directories included). Returns the class names."
  [declarations out-dir]
  (mapv (fn [{:keys [class] :as declaration}]
          (let [file (io/file out-dir (str (str/replace class "." "/") ".class"))]
            (io/make-parents file)
            (with-open [out (io/output-stream file)]
              (.write out ^bytes (generate-class declaration)))
            class))
        declarations))
