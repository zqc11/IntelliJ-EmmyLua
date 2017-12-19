/*
 * Copyright (c) 2017. tangzx(love.tangzx@qq.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.tang.intellij.lua.ty

import com.intellij.openapi.util.Computable
import com.intellij.psi.PsiElement
import com.intellij.psi.tree.IElementType
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.util.Processor
import com.tang.intellij.lua.Constants
import com.tang.intellij.lua.ext.recursionGuard
import com.tang.intellij.lua.project.LuaSettings
import com.tang.intellij.lua.psi.*
import com.tang.intellij.lua.psi.impl.LuaNameExprMixin
import com.tang.intellij.lua.search.SearchContext
import com.tang.intellij.lua.stubs.index.LuaClassMemberIndex

fun inferExpr(expr: LuaExpr?, context: SearchContext): ITy {
    return when (expr) {
        is LuaUnaryExpr -> expr.infer(context)
        is LuaBinaryExpr -> expr.infer(context)
        is LuaCallExpr -> expr.infer(context)
        is LuaClosureExpr -> infer(expr, context)
        is LuaTableExpr -> TyTable(expr)
        is LuaParenExpr -> inferExpr(expr.expr, context)
        is LuaNameExpr -> expr.infer(context)
        is LuaLiteralExpr -> expr.infer(context)
        is LuaIndexExpr -> expr.infer(context)
        null -> Ty.UNKNOWN
        else -> Ty.UNKNOWN
    }
}

private fun LuaUnaryExpr.infer(context: SearchContext): ITy {
    val stub = stub
    val operator = if (stub != null) stub.opType else unaryOp.node.firstChildNode.elementType

    return when (operator) {
        LuaTypes.MINUS -> inferExpr(expr, context) // Negative something
        LuaTypes.GETN -> Ty.NUMBER // Table length is a number
        else -> Ty.UNKNOWN
    }
}

private fun LuaBinaryExpr.infer(context: SearchContext): ITy {
    val stub = stub
    val operator = if (stub != null) stub.opType else {
        val firstChild = firstChild
        val nextVisibleLeaf = PsiTreeUtil.nextVisibleLeaf(firstChild)
        nextVisibleLeaf?.node?.elementType
    }
    var ty: ITy = Ty.UNKNOWN
    operator.let {
        ty = when (it) {
        //..
            LuaTypes.CONCAT -> Ty.STRING
        //<=, ==, <, ~=, >=, >
            LuaTypes.LE, LuaTypes.EQ, LuaTypes.LT, LuaTypes.NE, LuaTypes.GE, LuaTypes.GT -> Ty.BOOLEAN
        //and, or
            LuaTypes.AND, LuaTypes.OR -> guessAndOrType(this, operator, context)
        //&, <<, |, >>, ~, ^
            LuaTypes.BIT_AND, LuaTypes.BIT_LTLT, LuaTypes.BIT_OR, LuaTypes.BIT_RTRT, LuaTypes.BIT_TILDE, LuaTypes.EXP,
                //+, -, *, /, //, %
            LuaTypes.PLUS, LuaTypes.MINUS, LuaTypes.MULT, LuaTypes.DIV, LuaTypes.DOUBLE_DIV, LuaTypes.MOD -> guessBinaryOpType(this, operator, context)
            else -> Ty.UNKNOWN
        }
    }
    return ty
}

private fun guessAndOrType(binaryExpr: LuaBinaryExpr, operator: IElementType?, context:SearchContext): ITy {
    val lhs = binaryExpr.left
    val lty = inferExpr(lhs, context)
    return if (operator == LuaTypes.OR) {
        val rhs = binaryExpr.right
        if (rhs != null) lty.union(inferExpr(rhs, context)) else lty
    } else lty
}

private fun guessBinaryOpType(binaryExpr : LuaBinaryExpr, operator: IElementType?, context:SearchContext): ITy {
    val lhs = binaryExpr.left
    // TODO: Search for operator overrides
    return inferExpr(lhs, context)
}

private fun LuaCallExpr.infer(context: SearchContext): ITy {
    val luaCallExpr = this
    // xxx()
    val expr = luaCallExpr.expr
    // 从 require 'xxx' 中获取返回类型
    if (expr is LuaNameExpr && expr.name == Constants.WORD_REQUIRE) {
        var filePath: String? = null
        val string = luaCallExpr.firstStringArg
        if (string is LuaLiteralExpr) {
            filePath = string.stringValue
        }
        var file: LuaPsiFile? = null
        if (filePath != null)
            file = resolveRequireFile(filePath, luaCallExpr.project)
        if (file != null)
            return file.guessType(context)

        return Ty.UNKNOWN
    }

    var ret: ITy = Ty.UNKNOWN
    val ty = inferExpr(expr, context)//expr.guessTypeFromCache(context)
    TyUnion.each(ty) {
        when(it) {
            is ITyFunction -> {
                it.process(Processor { sig ->
                    ret = ret.union(sig.returnTy)
                    true
                })
            }
        //constructor : Class table __call
            is ITyClass -> ret = ret.union(it)
        }
    }

    //todo TyFunction
    if (Ty.isInvalid(ret)) {
        val bodyOwner = luaCallExpr.resolveFuncBodyOwner(context)
        if (bodyOwner != null)
            ret = inferReturnTy(bodyOwner, context)
    }

    // xxx.new()
    if (expr is LuaIndexExpr) {
        val fnName = expr.name
        if (fnName != null && LuaSettings.isConstructorName(fnName)) {
            ret = ret.union(expr.guessParentType(context))
        }
    }

    return ret
}

private fun LuaNameExpr.infer(context: SearchContext): ITy {
    val set = recursionGuard(this, Computable {
        var type:ITy = Ty.UNKNOWN
        val multiResolve = multiResolve(this, context)
        multiResolve.forEach {
            val set = getType(context, it)
            type = type.union(set)
        }

        if (Ty.isInvalid(type)) {
            type = type.union(getType(context, this))
        }

        type
    })
    return set ?: Ty.UNKNOWN
}

private fun getType(context: SearchContext, def: PsiElement): ITy {
    when (def) {
        is LuaNameExpr -> {
            //todo stub.module -> ty
            val stub = def.stub
            stub?.module?.let {
                val memberType = TySerializedClass(it).findMemberType(def.name, context)
                if (memberType != null && !Ty.isInvalid(memberType))
                    return memberType
            }

            var type: ITy = def.docTy ?: Ty.UNKNOWN
            //guess from value expr
            if (Ty.isInvalid(type)) {
                val stat = def.assignStat
                if (stat != null) {
                    val exprList = stat.valueExprList
                    if (exprList != null) {
                        context.index = stat.getIndexFor(def)
                        type = exprList.guessTypeAt(context)
                    }
                }
            }
            /*val stat = def.assignStat
            if (stat != null) {
                val comment = stat.comment
                //guess from comment
                if (comment != null)
                    type = comment.guessType(context)
                //guess from value expr
                if (Ty.isInvalid(type)) {
                    val exprList = stat.valueExprList
                    if (exprList != null) {
                        context.index = stat.getIndexFor(def)
                        type = exprList.guessTypeAt(context)
                    }
                }
            }*/

            //Global
            if (isGlobal(def)) {
                //use globalClassTy to store class members, that's very important
                type = type.union(TyClass.createGlobalType(def))
            }
            return type
        }
        is LuaTypeGuessable -> return def.guessTypeFromCache(context)
        else -> return Ty.UNKNOWN
    }
}

private fun isGlobal(nameExpr: LuaNameExpr):Boolean {
    val minx = nameExpr as LuaNameExprMixin
    val gs = minx.greenStub
    return gs?.isGlobal ?: (resolveLocal(nameExpr, null) == null)
}

private fun LuaLiteralExpr.infer(context: SearchContext): ITy {
    return when (this.kind) {
        LuaLiteralKind.Bool -> Ty.BOOLEAN
        LuaLiteralKind.String -> Ty.STRING
        LuaLiteralKind.Number -> Ty.NUMBER
        LuaLiteralKind.Nil -> Ty.NIL
        else -> Ty.UNKNOWN
    }
}

private fun LuaIndexExpr.infer(context: SearchContext): ITy {
    val retTy = recursionGuard(this, Computable {
        val indexExpr = this
        // xxx[yyy] as an array element?
        if (indexExpr.brack) {
            val tySet = indexExpr.guessParentType(context)

            // Type[]
            val array = TyUnion.find(tySet, ITyArray::class.java)
            if (array != null)
                return@Computable array.base

            // table<number, Type>
            val table = TyUnion.find(tySet, ITyGeneric::class.java)
            if (table != null)
                return@Computable table.getParamTy(1)
        }

        //from @type annotation
        val docTy = indexExpr.docTy
        if (docTy != null)
            return@Computable docTy

        // xxx.yyy = zzz
        //from value
        var result: ITy = Ty.UNKNOWN
        val valueTy: ITy = indexExpr.guessValueType(context)
        result = result.union(valueTy)

        //from other class member
        val propName = indexExpr.name
        if (propName != null) {
            val prefixType = indexExpr.guessParentType(context)
            TyUnion.each(prefixType) {
                if (it is TyClass) {
                    result = result.union(guessFieldType(propName, it, context))
                }
            }
        }
        result
    })

    return retTy ?: Ty.UNKNOWN
}

private fun guessFieldType(fieldName: String, type: ITyClass, context: SearchContext): ITy {
    var set:ITy = Ty.UNKNOWN

    LuaClassMemberIndex.processAll(type, fieldName, context, Processor {
        set = set.union(it.guessTypeFromCache(context))
        true
    })

    return set
}